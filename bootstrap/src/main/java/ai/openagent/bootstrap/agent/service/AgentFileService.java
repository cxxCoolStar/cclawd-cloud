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

    /**
     * 上传文件条目（前端 UploadedFile 形状：path 为 agent 相对路径）
     */
    public record UploadedFileEntry(String path, long size) {}

    /**
     * 保存上传文件到 workspace：sessionId 非空时落到该会话目录
     * （fastclaw handleAgentFileUpload 的作用域语义），文件名只取
     * 最后一段（剥离任何目录成分），同名覆盖
     */
    public List<UploadedFileEntry> saveUploads(String agentId, String sessionId, List<UploadFile> files) {
        Path dir = sessionId == null || sessionId.isBlank()
                ? agentHome(agentId)
                : agentHome(agentId).resolve("sessions").resolve(sessionId);
        try {
            Files.createDirectories(dir);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        Path root = agentHome(agentId).toAbsolutePath().normalize();
        List<UploadedFileEntry> saved = new java.util.ArrayList<>();
        for (UploadFile file : files) {
            String name = file.filename();
            int separator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
            if (separator >= 0) {
                name = name.substring(separator + 1);
            }
            if (name.isBlank() || ".".equals(name) || "..".equals(name)) {
                throw new ClientException("invalid filename", BaseErrorCode.PARAM_VERIFY_ERROR);
            }
            Path target = dir.resolve(name).normalize().toAbsolutePath();
            if (!target.startsWith(root)) {
                throw new ClientException("path escape", BaseErrorCode.PARAM_VERIFY_ERROR);
            }
            try {
                Files.write(target, file.content());
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
            saved.add(new UploadedFileEntry(
                    root.relativize(target).toString().replace('\\', '/'),
                    file.content().length));
        }
        return saved;
    }

    /**
     * 一个待保存的上传文件（Controller 从 multipart 解出后传入）
     */
    public record UploadFile(String filename, byte[] content) {}

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
