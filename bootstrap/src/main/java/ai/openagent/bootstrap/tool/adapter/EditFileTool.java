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
 * edit_file 工具 - 通过精确子串替换来编辑文件。
 *
 * <p>
 * 实现精确子串替换功能：old_string 必须唯一命中（除非 replace_all），
 * 错误消息设计用于引导用户重读文件或提供更多上下文，
 * 结果文本格式为 {@code Edited <path> (N replacement(s))}
 * </p>
 */
@Component
public class EditFileTool extends AbstractFileTool {

    public EditFileTool(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor(
                "edit_file",
                "Edit a file by replacing an exact substring. Prefer this over write_file when "
                        + "changing only part of a file: it's cheaper, can't drop unrelated content, and "
                        + "validates the replacement was applied. old_string must match a unique substring "
                        + "unless replace_all is true; new_string must differ from old_string. Read the "
                        + "file first if you're unsure of the exact text.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "path", Map.of(
                                        "type", "string",
                                        "description", "File path (relative to your workspace)"),
                                "old_string", Map.of(
                                        "type", "string",
                                        "description", "Exact text to replace. Must match a unique substring "
                                                + "in the file unless replace_all is true."),
                                "new_string", Map.of(
                                        "type", "string",
                                        "description", "Replacement text. Must differ from old_string."),
                                "replace_all", Map.of(
                                        "type", "boolean",
                                        "description", "Replace every occurrence of old_string instead of "
                                                + "requiring uniqueness. Defaults to false.")),
                        "required", List.of("path", "old_string", "new_string")),
                ToolDescriptor.Source.BUILTIN);
    }

    @Override
    protected ToolResult run(JsonNode args, ToolExecutionContext context) throws IOException {
        String path = requiredText(args, "path");
        if (path == null) {
            return missingArgument("path");
        }
        String validationError = WriteFileTool.validateFileTargetPath(path);
        if (validationError != null) {
            return ToolResult.failure(ToolErrorCode.TOOL_ARGUMENT_INVALID, validationError);
        }
        JsonNode oldNode = args.path("old_string");
        JsonNode newNode = args.path("new_string");
        if (!oldNode.isTextual() || !newNode.isTextual()) {
            return ToolResult.failure(
                    ToolErrorCode.TOOL_ARGUMENT_INVALID, "old_string and new_string are required");
        }
        String oldString = oldNode.asText();
        String newString = newNode.asText();
        boolean replaceAll = args.path("replace_all").asBoolean(false);

        // 前置校验：old_string 不能为空
        if (oldString.isEmpty()) {
            return ToolResult.failure(
                    ToolErrorCode.TOOL_ARGUMENT_INVALID,
                    "edit_file: old_string is empty (use write_file to create a file)");
        }
        if (oldString.equals(newString)) {
            return ToolResult.failure(
                    ToolErrorCode.TOOL_ARGUMENT_INVALID, "edit_file: new_string must differ from old_string");
        }

        Path target = WorkspacePaths.resolve(context.workspace(), path);
        if (!Files.exists(target) || Files.isDirectory(target)) {
            return ToolResult.failure(ToolErrorCode.FILE_NOT_FOUND, "file not found: " + path);
        }
        byte[] data = Files.readAllBytes(target);
        if (ReadFileTool.looksBinary(data)) {
            return ToolResult.success(ReadFileTool.binaryRefusal(path, data.length));
        }
        String content = new String(data, StandardCharsets.UTF_8);

        int count = countOccurrences(content, oldString);
        if (count == 0) {
            return ToolResult.failure(
                    ToolErrorCode.TOOL_EXECUTION_FAILED,
                    "edit_file: old_string not found in " + path + " — re-read the file and copy the "
                            + "exact text (whitespace/indentation matters)");
        }
        if (count > 1 && !replaceAll) {
            return ToolResult.failure(
                    ToolErrorCode.TOOL_EXECUTION_FAILED,
                    "edit_file: old_string matches " + count + " locations in " + path + " — provide "
                            + "more surrounding context to make it unique, or set replace_all=true");
        }
        String updated = replaceAll
                ? content.replace(oldString, newString)
                : content.replaceFirst(java.util.regex.Pattern.quote(oldString),
                        java.util.regex.Matcher.quoteReplacement(newString));
        int replacements = replaceAll ? count : 1;
        Files.write(target, updated.getBytes(StandardCharsets.UTF_8));
        return ToolResult.success("Edited " + path + " (" + replacements + " replacement(s))");
    }

    private static int countOccurrences(String content, String needle) {
        int count = 0;
        int index = 0;
        while ((index = content.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
