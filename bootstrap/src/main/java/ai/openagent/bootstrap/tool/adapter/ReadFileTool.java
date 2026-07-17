package ai.openagent.bootstrap.tool.adapter;

import ai.openagent.agent.tool.ToolDescriptor;
import ai.openagent.agent.tool.ToolErrorCode;
import ai.openagent.agent.tool.ToolExecutionContext;
import ai.openagent.agent.tool.ToolResult;
import ai.openagent.bootstrap.tool.config.ToolProperties;
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
 * read_file 工具（对齐 fastclaw file.go makeReadFile）
 *
 * <p>
 * 与 fastclaw 一致的行为：二进制文件（前 8KB 含 NUL 字节）拒绝读取并
 * 返回引导性文本（防止字节涌入上下文）；成功时结果即文件原文。
 * V2 附加：单文件大小上限（方案 3.1 默认 1 MiB），超限返回 FILE_TOO_LARGE
 * </p>
 */
@Component
public class ReadFileTool extends AbstractFileTool {

    private final ToolProperties toolProperties;

    public ReadFileTool(ObjectMapper objectMapper, ToolProperties toolProperties) {
        super(objectMapper);
        this.toolProperties = toolProperties;
    }

    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor(
                "read_file",
                "Read the contents of a file",
                Map.of(
                        "type", "object",
                        "properties", Map.of("path", Map.of(
                                "type", "string",
                                "description", "File path (relative to your workspace)")),
                        "required", List.of("path")),
                ToolDescriptor.Source.BUILTIN);
    }

    @Override
    protected ToolResult run(JsonNode args, ToolExecutionContext context) throws IOException {
        String path = requiredText(args, "path");
        if (path == null) {
            return missingArgument("path");
        }
        Path target = WorkspacePaths.resolve(context.workspace(), path);
        if (!Files.exists(target) || Files.isDirectory(target)) {
            return ToolResult.failure(ToolErrorCode.FILE_NOT_FOUND, "file not found: " + path);
        }
        long size = Files.size(target);
        if (size > toolProperties.readFileMaxBytes()) {
            return ToolResult.failure(
                    ToolErrorCode.FILE_TOO_LARGE,
                    "file is " + size + " bytes, exceeds the " + toolProperties.readFileMaxBytes()
                            + " byte read limit");
        }
        byte[] data = Files.readAllBytes(target);
        if (looksBinary(data)) {
            // fastclaw binaryRefusal 语义：拒绝读取但作为成功 observation
            // 返回引导文本（不是错误——模型应改走其他路径而非重试）
            return ToolResult.success(binaryRefusal(path, data.length));
        }
        return ToolResult.success(new String(data, StandardCharsets.UTF_8));
    }

    /**
     * 前 8KB 含 NUL 字节即视为二进制（fastclaw looksBinary 同规则）
     */
    static boolean looksBinary(byte[] data) {
        int limit = Math.min(data.length, 8192);
        for (int i = 0; i < limit; i++) {
            if (data[i] == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 二进制拒绝文本（fastclaw binaryRefusal 语义，按 V2 无 skill 环境收窄）
     */
    static String binaryRefusal(String path, int size) {
        return "[read_file refused: \"" + path + "\" is a binary file (" + size
                + " bytes). Binary bytes don't decode as text — loading them would blow past the "
                + "context window. Don't probe the file further; tell the user this file format "
                + "can't be read as text.]";
    }
}
