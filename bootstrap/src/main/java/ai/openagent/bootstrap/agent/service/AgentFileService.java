package ai.openagent.bootstrap.agent.service;

import ai.openagent.bootstrap.tool.config.ToolProperties;
import ai.openagent.framework.errorcode.BaseErrorCode;
import ai.openagent.framework.exception.ClientException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/**
 * Agent workspace 文件服务（对照 fastclaw handleAgentFileList /
 * handleAgentFile）
 *
 * <p>
 * 宿主布局与 fastclaw 一致：{workspaceRoot}/{agentId}/ 为 agent 根，
 * 会话文件在 sessions/{sessionId}/ 下。返回路径保持 agent 相对
 * （如 "sessions/{sid}/fib.py"），与前端 FileTreeView 的 rootPrefix
 * 及 fileUrl 消费方式对齐；sessionId 非空时按会话作用域过滤
 * </p>
 */
@Service
public class AgentFileService {

    /**
     * 前端 WorkspaceFile 形状：path（agent 相对）、size（字节）、modTime（秒）
     */
    public record WorkspaceFileEntry(String path, long size, long modTime) {}

    private final ToolProperties toolProperties;

    public AgentFileService(ToolProperties toolProperties) {
        this.toolProperties = toolProperties;
    }

    /**
     * 列出 workspace 文件。sessionId 非空时只返回该会话作用域
     * （sessions/{sessionId}/ 前缀下）的文件；为空返回 agent 全部文件
     */
    public List<WorkspaceFileEntry> listFiles(String agentId, String sessionId) {
        Path root = agentHome(agentId);
        Path scope = sessionId == null || sessionId.isBlank()
                ? root
                : root.resolve("sessions").resolve(sessionId);
        if (!Files.isDirectory(scope)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(scope)) {
            return walk.filter(Files::isRegularFile)
                    .map(path -> toEntry(root, path))
                    .sorted(Comparator.comparing(WorkspaceFileEntry::path))
                    .toList();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    /**
     * 解析 agent 相对路径为宿主路径；越界 400、不存在 404
     * （fastclaw handleAgentFile 的 path escape 防护）
     */
    public Path resolveFile(String agentId, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new ClientException("path required", BaseErrorCode.PARAM_VERIFY_ERROR);
        }
        Path root = agentHome(agentId).toAbsolutePath().normalize();
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            throw new ClientException("path escape", BaseErrorCode.PARAM_VERIFY_ERROR);
        }
        if (!Files.isRegularFile(resolved)) {
            throw new ClientException("file not found", BaseErrorCode.RESOURCE_NOT_FOUND);
        }
        return resolved;
    }

    private WorkspaceFileEntry toEntry(Path root, Path path) {
        try {
            String relative = root.relativize(path).toString().replace('\\', '/');
            return new WorkspaceFileEntry(
                    relative,
                    Files.size(path),
                    Files.getLastModifiedTime(path).toMillis() / 1000);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private Path agentHome(String agentId) {
        return Path.of(toolProperties.workspaceRoot()).resolve(agentId);
    }
}
