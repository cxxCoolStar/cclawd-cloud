package ai.openagent.bootstrap.tool.adapter;

import ai.openagent.agent.tool.ToolDescriptor;
import ai.openagent.agent.tool.ToolExecutionContext;
import ai.openagent.agent.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * list_dir 工具（对齐 fastclaw file.go makeListDir）
 *
 * <p>
 * 结果格式与 fastclaw 逐行一致：目录 {@code d <name>/}，
 * 文件 {@code f <name> (<size> bytes)}。目录不存在时返回空列表
 * （workspace 惰性创建，空会话列目录不是错误）
 * </p>
 */
@Component
public class ListDirTool extends AbstractFileTool {

    public ListDirTool(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor(
                "list_dir",
                "List files and directories in a path",
                Map.of(
                        "type", "object",
                        "properties", Map.of("path", Map.of(
                                "type", "string",
                                "description", "Directory path (relative to your workspace, use \".\" for the root)")),
                        "required", java.util.List.of("path")),
                ToolDescriptor.Source.BUILTIN);
    }

    @Override
    protected ToolResult run(JsonNode args, ToolExecutionContext context) throws IOException {
        String path = requiredText(args, "path");
        if (path == null) {
            return missingArgument("path");
        }
        Path target = WorkspacePaths.resolve(context.workspace(), path);
        if (!Files.exists(target)) {
            return ToolResult.success("");
        }
        if (!Files.isDirectory(target)) {
            return ToolResult.failure(
                    ai.openagent.agent.tool.ToolErrorCode.TOOL_EXECUTION_FAILED,
                    "not a directory: " + path);
        }
        StringBuilder listing = new StringBuilder();
        try (Stream<Path> entries = Files.list(target)) {
            for (Path entry : entries.sorted().toList()) {
                if (Files.isDirectory(entry)) {
                    listing.append("d ").append(entry.getFileName()).append("/\n");
                } else {
                    listing.append("f ").append(entry.getFileName())
                            .append(" (").append(Files.size(entry)).append(" bytes)\n");
                }
            }
        }
        return ToolResult.success(listing.toString());
    }
}
