package ai.openagent.bootstrap.tool.adapter;

import ai.openagent.agent.tool.ToolDescriptor;
import ai.openagent.agent.tool.ToolErrorCode;
import ai.openagent.agent.tool.ToolExecutionContext;
import ai.openagent.agent.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * write_file 工具 - 将内容写入文件（必要时自动创建目录）
 *
 * <p>
 * 结果文本 {@code Written N bytes to <path>} 必须与前端期望的格式一致——
 * 前端 chat-screen.tsx 依赖该格式刷新文件面板（V2 方案 20.3）。
 * 字节数按 UTF-8 编码后的字节长度计算
 * </p>
 */
@Component
public class WriteFileTool extends AbstractFileTool {

    public WriteFileTool(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor(
                "write_file",
                "Write content to a file (creates directories as needed)",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "path", Map.of(
                                        "type", "string",
                                        "description", "File path (relative to your workspace)"),
                                "content", Map.of(
                                        "type", "string",
                                        "description", "Content to write")),
                        "required", List.of("path", "content")),
                ToolDescriptor.Source.BUILTIN);
    }

    @Override
    protected ToolResult run(JsonNode args, ToolExecutionContext context) throws IOException {
        String path = requiredText(args, "path");
        if (path == null) {
            return missingArgument("path");
        }
        JsonNode contentNode = args.path("content");
        if (!contentNode.isTextual()) {
            return missingArgument("content");
        }
        String validationError = validateFileTargetPath(path);
        if (validationError != null) {
            return ToolResult.failure(ToolErrorCode.TOOL_ARGUMENT_INVALID, validationError);
        }
        String content = contentNode.asText();
        Path target = WorkspacePaths.resolve(context.workspace(), path);
        if (Files.exists(target) && !Files.isDirectory(target)) {
            return ToolResult.failure(
                    ToolErrorCode.TOOL_ARGUMENT_INVALID,
                    "file already exists; confirmation required before overwrite: " + path);
        }
        if (Files.isDirectory(target)) {
            return ToolResult.failure(
                    ToolErrorCode.TOOL_ARGUMENT_INVALID,
                    "write_file: \"" + path + "\" resolves to a directory; include a filename in the path");
        }
        Files.createDirectories(target.getParent());
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        Files.write(target, bytes);
        // 结果格式需与前端解析逻辑匹配：前端靠 "Written N bytes" 前缀刷新文件面板
        return ToolResult.success("Written " + bytes.length + " bytes to " + path);
    }

    /**
     * 写类操作的目标路径校验 - 验证路径是否指向有效的文件位置
     */
    static String validateFileTargetPath(String path) {
        String trimmed = path.trim();
        if (trimmed.isEmpty()) {
            return "path is required and must include a filename";
        }
        if (trimmed.endsWith("/") || trimmed.endsWith("\\")) {
            return "path \"" + path + "\" ends in a separator; include a filename at the end";
        }
        Path normalized = Path.of(trimmed).normalize();
        String name = normalized.toString();
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.equals("/")) {
            return "path \"" + path + "\" is a directory, not a file; include a filename";
        }
        return null;
    }
}
