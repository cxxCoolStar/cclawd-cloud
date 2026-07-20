package ai.openagent.bootstrap.sandbox;

import ai.openagent.bootstrap.config.ConfigService;
import ai.openagent.bootstrap.sandbox.config.SandboxProperties;
import ai.openagent.bootstrap.tool.config.ToolProperties;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Docker 沙箱服务（V4 方案 4.2）
 *
 * <p>
 * 核心功能：使用 docker CLI 管理长驻容器（{@code tail -f /dev/null}）、
 * 首次 exec 懒创建、agent 级容器复用、agent home 绑定挂载到 /workspace、
 * 资源限额（cpus/memory/network）、关闭时 {@code docker rm -f}。
 * 有意收缩：不做 skill 挂载、端口发布与代理继承（V4 范围外）。
 * 安全红线：命令只经 {@code docker exec} 进入容器，本类不提供任何宿主机
 * shell 执行路径
 * </p>
 */
@Slf4j
@Service
public class DockerSandboxService {

    /**
     * 沙箱不可用/执行失败（ExecTool 映射为工具级失败结果）
     */
    public static class SandboxException extends RuntimeException {
        public SandboxException(String message) {
            super(message);
        }
    }

    /**
     * 一次容器内命令执行的结果
     *
     * @param exitCode 命令退出码（0 成功）
     * @param output   stdout+stderr 合并文本
     * @param timedOut 是否超时强制终止
     */
    public record ExecOutcome(int exitCode, String output, boolean timedOut) {}

    static final String CONTAINER_LABEL = "openagent=sandbox";
    static final String CONTAINER_WORKSPACE = "/workspace";

    private final SandboxProperties sandboxProperties;
    private final ToolProperties toolProperties;
    private final DockerCli dockerCli;
    private final ConfigService configService;
    private final Map<String, Object> agentLocks = new ConcurrentHashMap<>();

    public DockerSandboxService(
            SandboxProperties sandboxProperties,
            ToolProperties toolProperties,
            DockerCli dockerCli,
            ConfigService configService) {
        this.sandboxProperties = sandboxProperties;
        this.toolProperties = toolProperties;
        this.dockerCli = dockerCli;
        this.configService = configService;
    }

    /**
     * Docker 沙箱全局开关：/api/config 的 DB 覆盖优先（V7 方案 3.2），
     * 缺省回退 {@code openagent.sandbox.docker-enabled} 属性值
     */
    public boolean dockerEnabled() {
        return configService.sandboxEnabledOverride().orElse(sandboxProperties.dockerEnabled());
    }

    /**
     * 在 agent 的沙箱容器中执行命令
     *
     * @param relativeWorkdir 相对会话 workspace 的子目录（空为会话根）；
     *                        必须已校验无逃逸
     * @param timeout         执行超时（调用方已按工具/run deadline 收敛）
     */
    public ExecOutcome exec(
            String agentId, String sessionId, String command, String relativeWorkdir, Duration timeout) {
        // 绑定挂载的宿主目录必须先存在，否则 docker 会以 root 建出宿主目录
        Path sessionWorkspace = sessionWorkspace(agentId, sessionId);
        try {
            Files.createDirectories(sessionWorkspace);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        String containerWorkdir = CONTAINER_WORKSPACE + "/sessions/" + sessionId
                + (relativeWorkdir.isBlank() ? "" : "/" + relativeWorkdir);

        String container = ensureContainer(agentId);
        DockerCli.CliResult result = dockerCli.run(
                List.of("exec", "-w", containerWorkdir, container, "sh", "-c", command), timeout);
        if (result.output().contains("No such container")) {
            // 容器被外部删除：驱逐缓存并重建后重试一次
            log.warn("[sandbox] 容器被外部移除，重建后重试，agentId={}", agentId);
            synchronized (lockOf(agentId)) {
                dockerCli.run(List.of("rm", "-f", containerName(agentId)));
            }
            container = ensureContainer(agentId);
            result = dockerCli.run(
                    List.of("exec", "-w", containerWorkdir, container, "sh", "-c", command), timeout);
        }
        if (result.timedOut()) {
            return new ExecOutcome(DockerCli.CliResult.TIMED_OUT, result.output(), true);
        }
        return new ExecOutcome(result.exitCode(), result.output(), false);
    }

    /**
     * 懒创建/复用 agent 级容器（create + start；
     * 已运行则复用，已停止则启动，启动失败则删除重建）
     */
    String ensureContainer(String agentId) {
        String name = containerName(agentId);
        synchronized (lockOf(agentId)) {
            DockerCli.CliResult inspect =
                    dockerCli.run(List.of("inspect", "-f", "{{.State.Running}}", name));
            if (inspect.success() && "true".equals(inspect.output())) {
                return name;
            }
            if (inspect.success()) {
                DockerCli.CliResult start = dockerCli.run(List.of("start", name));
                if (start.success()) {
                    log.info("[sandbox] 已停止容器重新启动，agentId={}, container={}", agentId, name);
                    return name;
                }
                log.warn("[sandbox] 容器启动失败，删除重建，agentId={}, output={}", agentId, start.output());
                dockerCli.run(List.of("rm", "-f", name));
            }
            List<String> createArgs = buildCreateArgs(agentId, name);
            DockerCli.CliResult create = dockerCli.run(createArgs);
            if (!create.success()) {
                throw new SandboxException("docker create failed: " + create.output());
            }
            DockerCli.CliResult start = dockerCli.run(List.of("start", name));
            if (!start.success()) {
                dockerCli.run(List.of("rm", "-f", name));
                throw new SandboxException("docker start failed: " + start.output());
            }
            log.info("[sandbox] 容器已创建，agentId={}, container={}, image={}",
                    agentId, name, sandboxProperties.image());
            return name;
        }
    }

    /**
     * create 参数构造（单列便于单测）：标签、名称、workspace 挂载、
     * 限额、网络、常驻命令
     */
    List<String> buildCreateArgs(String agentId, String name) {
        String hostPath = Path.of(toolProperties.workspaceRoot())
                .resolve(agentId)
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace('\\', '/');
        List<String> args = new ArrayList<>(List.of(
                "create", "--interactive",
                "--label", CONTAINER_LABEL,
                "--name", name,
                "-v", hostPath + ":" + CONTAINER_WORKSPACE + ":rw",
                "-w", CONTAINER_WORKSPACE));
        if (!sandboxProperties.cpus().isBlank()) {
            args.addAll(List.of("--cpus", sandboxProperties.cpus()));
        }
        if (!sandboxProperties.memory().isBlank()) {
            args.addAll(List.of("--memory", sandboxProperties.memory()));
        }
        if (!sandboxProperties.network().isBlank()) {
            args.addAll(List.of("--network", sandboxProperties.network()));
        }
        args.addAll(List.of(sandboxProperties.image(), "tail", "-f", "/dev/null"));
        return args;
    }

    /**
     * 容器名：openagent-sandbox-{agentId}（docker 名称字符集之外的字符替换为 -）
     */
    static String containerName(String agentId) {
        return "openagent-sandbox-" + agentId.replaceAll("[^a-zA-Z0-9_.-]", "-");
    }

    Path sessionWorkspace(String agentId, String sessionId) {
        return Path.of(toolProperties.workspaceRoot())
                .resolve(agentId)
                .resolve("sessions")
                .resolve(sessionId);
    }

    /**
     * 启动时清理上次进程遗留的沙箱容器（尽力而为，失败只告警）
     */
    @EventListener(ApplicationReadyEvent.class)
    public void cleanupStaleContainers() {
        if (!dockerEnabled()) {
            return;
        }
        try {
            DockerCli.CliResult list =
                    dockerCli.run(List.of("ps", "-aq", "--filter", "label=" + CONTAINER_LABEL));
            if (!list.success() || list.output().isBlank()) {
                return;
            }
            for (String id : list.output().split("\\R")) {
                if (!id.isBlank()) {
                    log.info("[sandbox] 清理遗留沙箱容器，id={}", id.trim());
                    dockerCli.run(List.of("rm", "-f", id.trim()));
                }
            }
        } catch (RuntimeException error) {
            log.warn("[sandbox] 遗留容器清理失败", error);
        }
    }

    /**
     * 进程关闭时移除本进程可见的沙箱容器（close 语义，尽力而为）
     */
    @PreDestroy
    public void closeAll() {
        if (!dockerEnabled()) {
            return;
        }
        cleanupStaleContainers();
    }

    private Object lockOf(String agentId) {
        return agentLocks.computeIfAbsent(agentId, key -> new Object());
    }
}
