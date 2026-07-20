package ai.openagent.bootstrap.workspace;

import ai.openagent.bootstrap.tool.config.ToolProperties;
import ai.openagent.bootstrap.workspace.config.WorkspaceProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Workspace 版本历史服务
 *
 * <p>
 * 设计说明：
 * <ul>
 *   <li>bare 仓库在 workspace 外：{workspaceRoot}/.history/{agentId}/{sessionId}.git，
 *       agent 的文件工具（会话作用域）与列表接口都看不到它；</li>
 *   <li>运行结束（turn 边界）提交一次快照——exec 在容器内经绑定挂载的写入
 *       宿主进程感知不到，按写钩子无法完整，turn 边界是唯一能覆盖
 *       文件工具 + exec + 上传的一致性时点；</li>
 *   <li>尽力而为：任何历史操作失败只 WARN，绝不影响 agent 运行。</li>
 * </ul>
 * 回滚当前通过 git 命令手动进行（git --git-dir=... --work-tree=... log/checkout）
 * </p>
 */
@Slf4j
@Service
public class WorkspaceHistoryService {

    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(30);

    private final ToolProperties toolProperties;
    private final WorkspaceProperties workspaceProperties;
    private final boolean gitAvailable;

    public WorkspaceHistoryService(ToolProperties toolProperties, WorkspaceProperties workspaceProperties) {
        this.toolProperties = toolProperties;
        this.workspaceProperties = workspaceProperties;
        this.gitAvailable = probeGit();
    }

    public boolean enabled() {
        return workspaceProperties.historyEnabled() && gitAvailable;
    }

    /**
     * 运行结束后提交会话 workspace 快照（空提交跳过；全部异常吞掉只告警）
     */
    public void commitAfterRun(String agentId, String sessionId, String runId) {
        if (!enabled()) {
            return;
        }
        Path workspace = Path.of(toolProperties.workspaceRoot())
                .resolve(agentId).resolve("sessions").resolve(sessionId);
        if (!Files.isDirectory(workspace)) {
            return;
        }
        try {
            Path repo = historyRepo(agentId, sessionId);
            if (!Files.isDirectory(repo)) {
                Files.createDirectories(repo.getParent());
                // 注意：git init 与 --work-tree 组合会被拒绝
                // （GIT_WORK_TREE not allowed without GIT_DIR），
                // init 用裸路径形式，后续命令才带 --git-dir/--work-tree
                runGitPlain("git", "init", "--bare", repo.toString());
            }
            runGit(repo, workspace, "add", "-A");
            if (runGit(repo, workspace, "status", "--porcelain").isBlank()) {
                return; // 无变更，空提交跳过
            }
            // 内联身份：不依赖用户全局 git config
            runGit(repo, workspace, "-c", "user.name=OpenAgent", "-c", "user.email=openagent@localhost",
                    "commit", "-m", "turn " + runId, "--quiet");
            log.info("[workspace-history] 快照已提交，agentId={}, sessionId={}, runId={}",
                    agentId, sessionId, runId);
        } catch (java.io.IOException | RuntimeException error) {
            // 尽力而为：历史失败绝不影响 agent 运行
            log.warn("[workspace-history] 快照失败，已跳过，agentId={}, sessionId={}, error={}",
                    agentId, sessionId, error.getMessage());
        }
    }

    /**
     * 一次历史提交（回滚列表用）
     */
    public record HistoryEntry(String hash, String message, long time) {}

    /**
     * 列出会话的历史提交（新到旧）
     */
    public List<HistoryEntry> listHistory(String agentId, String sessionId) {
        Path repo = historyRepo(agentId, sessionId);
        if (!Files.isDirectory(repo)) {
            return List.of();
        }
        Path workTree = sessionWorkspace(agentId, sessionId);
        String output = runGit(repo, workTree, "log", "--pretty=%H|%s|%ct");
        if (output.isBlank()) {
            return List.of();
        }
        return output.lines()
                .map(line -> line.split("\\|", 3))
                .filter(parts -> parts.length == 3)
                .map(parts -> new HistoryEntry(parts[0], parts[1], Long.parseLong(parts[2])))
                .toList();
    }

    /**
     * 回滚会话 workspace 到指定提交（整树恢复；提交哈希先校验防注入）
     */
    public void restore(String agentId, String sessionId, String commit) {
        if (commit == null || !commit.matches("[0-9a-fA-F]{7,40}")) {
            throw new ai.openagent.framework.exception.ClientException(
                    "invalid commit hash", ai.openagent.framework.errorcode.BaseErrorCode.PARAM_VERIFY_ERROR);
        }
        Path repo = historyRepo(agentId, sessionId);
        if (!Files.isDirectory(repo)) {
            throw new ai.openagent.framework.exception.ClientException(
                    "no history for this session", ai.openagent.framework.errorcode.BaseErrorCode.RESOURCE_NOT_FOUND);
        }
        runGit(repo, sessionWorkspace(agentId, sessionId), "checkout", commit, "--", ".");
        log.info("[workspace-history] 已回滚，agentId={}, sessionId={}, commit={}", agentId, sessionId, commit);
    }

    private Path sessionWorkspace(String agentId, String sessionId) {
        return Path.of(toolProperties.workspaceRoot())
                .resolve(agentId).resolve("sessions").resolve(sessionId);
    }

    /**
     * 会话的历史仓库路径（workspace 外）
     */
    public Path historyRepo(String agentId, String sessionId) {
        return Path.of(toolProperties.workspaceRoot())
                .resolve(".history").resolve(agentId).resolve(sessionId + ".git");
    }

    /**
     * 执行 git 命令并返回 stdout（异常抛出 RuntimeException 由调用方兜底）
     */
    private String runGit(Path repo, Path workTree, String... args) {
        List<String> command = new ArrayList<>(List.of(
                "git", "--git-dir=" + repo, "--work-tree=" + workTree));
        command.addAll(List.of(args));
        return runGitPlain(command.toArray(new String[0]));
    }

    /**
     * 执行任意 git 命令（init 等不能带 --git-dir/--work-tree 的场景）
     */
    private static String runGitPlain(String... command) {
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException error) {
            throw new IllegalStateException("failed to launch git: " + error.getMessage(), error);
        }
        boolean finished;
        try {
            finished = process.waitFor(GIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IllegalStateException("git interrupted");
        }
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("git timed out: " + command);
        }
        String output;
        try {
            output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException error) {
            throw new IllegalStateException("git output read failed", error);
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("git failed (" + process.exitValue() + "): " + output);
        }
        return output;
    }

    private static boolean probeGit() {
        try {
            return new ProcessBuilder("git", "--version").start().waitFor(10, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }
}
