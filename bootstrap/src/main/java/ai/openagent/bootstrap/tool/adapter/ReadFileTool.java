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
 * read_file 工具 - 读取文件内容
 *
 * <p>
 * 功能说明：
 * - 读取指定路径的文本文件内容并返回原文
 * - 二进制文件检测：若文件前 8KB 含 NUL 字节则视为二进制文件，拒绝读取并
 *   返回引导性文本（防止字节涌入上下文）
 * - 单文件大小限制：超过配置上限（默认 1 MiB）返回 FILE_TOO_LARGE
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
            // 二进制文件拒绝读取：返回引导文本而非错误
            // 模型应改走其他路径而非重试
            return ToolResult.success(binaryRefusal(path, data.length));
        }
        return ToolResult.success(new String(data, StandardCharsets.UTF_8));
    }

    /**
     * 检测文件内容是否为二进制文件
     * 规则：扫描前 8KB，若包含 NUL 字节则视为二进制
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
     * 生成二进制文件拒绝读取的引导文本
     */
    static String binaryRefusal(String path, int size) {
        return "[read_file refused: \"" + path + "\" is a binary file (" + size
                + " bytes). Binary bytes don't decode as text — loading them would blow past the "
                + "context window. Don't probe the file further; tell the user this file format "
                + "can't be read as text.]";
    }
}
