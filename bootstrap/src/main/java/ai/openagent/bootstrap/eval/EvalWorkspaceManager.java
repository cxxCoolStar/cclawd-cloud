package ai.openagent.bootstrap.eval;

import ai.openagent.agent.eval.EvalFixture;
import ai.openagent.agent.eval.EvalFixture.FileFixture;
import ai.openagent.bootstrap.memory.MemoryService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Eval 工作空间管理器
 * - Before Run：创建 eval-workspaces/{runId}/
 * - After Run：清理（如配置开启）
 * - 提供 fixture 创建方法
 */
@Slf4j
@Component
public class EvalWorkspaceManager {

    @Value("${openagent.eval.workspace-root:./data/eval-workspaces}")
    private String workspaceRoot;

    @Value("${openagent.eval.cleanup-on-finish:true}")
    private boolean cleanupOnFinish;

    private final MemoryService memoryService;
    private Path rootPath;

    @Autowired
    public EvalWorkspaceManager(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @PostConstruct
    public void init() {
        this.rootPath = Paths.get(workspaceRoot).toAbsolutePath().normalize();
        try {
            Files.createDirectories(rootPath);
            log.info("EvalWorkspaceManager initialized with root: {}", rootPath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create workspace root: " + rootPath, e);
        }
    }

    /**
     * 创建新的运行工作空间
     *
     * @param runId 运行 ID（可为 null，自动生成）
     * @return 工作空间路径
     */
    public Path createRunWorkspace(String runId) {
        String actualRunId = runId != null ? runId : UUID.randomUUID().toString();
        Path runWorkspace = rootPath.resolve(actualRunId);

        try {
            Files.createDirectories(runWorkspace);
            // 创建内部目录结构
            Files.createDirectories(runWorkspace.resolve("workspace"));
            Files.createDirectories(runWorkspace.resolve("output"));
            Files.createDirectories(runWorkspace.resolve("temp"));

            log.info("Created eval run workspace: {}", runWorkspace);
            return runWorkspace;
        } catch (IOException e) {
            throw new RuntimeException("Failed to create run workspace: " + runWorkspace, e);
        }
    }

    /**
     * 获取运行工作空间路径
     *
     * @param runId 运行 ID
     * @return 工作空间路径
     */
    public Path getRunWorkspace(String runId) {
        return rootPath.resolve(runId);
    }

    /**
     * 获取 workspace 子目录路径
     *
     * @param runId 运行 ID
     * @return workspace 路径
     */
    public Path getWorkspaceDir(String runId) {
        return getRunWorkspace(runId).resolve("workspace");
    }

    /**
     * 清理运行工作空间
     *
     * @param runId 运行 ID
     */
    public void cleanupRunWorkspace(String runId) {
        if (!cleanupOnFinish) {
            log.info("Cleanup disabled for run: {}", runId);
            return;
        }

        Path runWorkspace = getRunWorkspace(runId);
        try {
            deleteDirectory(runWorkspace);
            log.info("Cleaned up eval run workspace: {}", runWorkspace);
        } catch (IOException e) {
            log.warn("Failed to cleanup workspace {}: {}", runWorkspace, e.getMessage());
        }
    }

    /**
     * 创建夹具（仅文件系统）
     *
     * @param runId   运行 ID
     * @param fixture 夹具定义
     */
    public void createFixtures(String runId, EvalFixture fixture) {
        createFixtures(runId, null, fixture);
    }

    /**
     * 创建夹具（完整版，支持 memory）
     *
     * @param runId   运行 ID
     * @param agentId Agent ID（用于 memory fixtures，可为 null）
     * @param fixture 夹具定义
     */
    public void createFixtures(String runId, String agentId, EvalFixture fixture) {
        if (fixture == null) {
            return;
        }

        Path workspaceDir = getWorkspaceDir(runId);

        // 创建文件夹具
        List<FileFixture> files = fixture.getFiles();
        if (files != null) {
            for (FileFixture fileFixture : files) {
                createFileFixture(workspaceDir, fileFixture);
            }
        }

        // 创建 memory 夹具
        List<String> memory = fixture.getMemory();
        if (memory != null && !memory.isEmpty() && agentId != null) {
            createMemoryFixture(agentId, memory);
        }

        // 注：skills 夹具预留
    }

    /**
     * 创建 memory 夹具
     */
    private void createMemoryFixture(String agentId, List<String> memory) {
        if (!memoryService.enabled()) {
            log.warn("Memory service is disabled, skipping memory fixtures");
            return;
        }

        String content = String.join("\n- ", memory);
        if (!content.startsWith("- ")) {
            content = "- " + content;
        }

        try {
            // 使用 eval 专用 userId 以隔离测试数据
            memoryService.saveMemory("eval-user", agentId, content);
            log.debug("Created memory fixture for agent {}: {} entries", agentId, memory.size());
        } catch (Exception e) {
            log.error("Failed to create memory fixture for agent {}: {}", agentId, e.getMessage());
        }
    }

    /**
     * 创建单个文件夹具
     */
    private void createFileFixture(Path workspaceDir, FileFixture fileFixture) {
        if (fileFixture == null || fileFixture.getPath() == null) {
            return;
        }

        String relativePath = fileFixture.getPath();
        // 处理可能以 workspace/ 开头的路径
        if (relativePath.startsWith("workspace/")) {
            relativePath = relativePath.substring("workspace/".length());
        }

        Path filePath = workspaceDir.resolve(relativePath).normalize();

        // 安全检查：确保文件在 workspace 目录内
        if (!filePath.startsWith(workspaceDir)) {
            log.warn("Skipping unsafe file path: {}", fileFixture.getPath());
            return;
        }

        try {
            // 创建父目录
            Files.createDirectories(filePath.getParent());

            // 写入文件内容
            String content = fileFixture.getContent() != null ? fileFixture.getContent() : "";
            Files.writeString(filePath, content);

            log.debug("Created fixture file: {}", filePath);
        } catch (IOException e) {
            log.error("Failed to create fixture file {}: {}", filePath, e.getMessage());
            throw new RuntimeException("Failed to create fixture file: " + filePath, e);
        }
    }

    /**
     * 检查文件是否存在
     */
    public boolean fileExists(String runId, String relativePath) {
        Path workspaceDir = getWorkspaceDir(runId);
        String cleanPath = relativePath;
        if (relativePath.startsWith("workspace/")) {
            cleanPath = relativePath.substring("workspace/".length());
        }
        Path filePath = workspaceDir.resolve(cleanPath).normalize();
        return Files.exists(filePath) && Files.isRegularFile(filePath);
    }

    /**
     * 读取文件内容
     */
    public String readFile(String runId, String relativePath) {
        Path workspaceDir = getWorkspaceDir(runId);
        String cleanPath = relativePath;
        if (relativePath.startsWith("workspace/")) {
            cleanPath = relativePath.substring("workspace/".length());
        }
        Path filePath = workspaceDir.resolve(cleanPath).normalize();

        try {
            if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
                return Files.readString(filePath);
            }
        } catch (IOException e) {
            log.warn("Failed to read file {}: {}", filePath, e.getMessage());
        }
        return null;
    }

    /**
     * 获取根路径
     */
    public Path getRootPath() {
        return rootPath;
    }

    /**
     * 递归删除目录
     */
    private void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }

        try (var stream = Files.walk(dir)) {
            stream.sorted(Collections.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            log.warn("Failed to delete {}: {}", path, e.getMessage());
                        }
                    });
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("EvalWorkspaceManager shutdown");
    }
}
