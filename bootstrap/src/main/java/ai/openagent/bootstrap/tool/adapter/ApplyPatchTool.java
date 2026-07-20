package ai.openagent.bootstrap.tool.adapter;

import ai.openagent.agent.tool.ToolDescriptor;
import ai.openagent.agent.tool.ToolErrorCode;
import ai.openagent.agent.tool.ToolExecutionContext;
import ai.openagent.agent.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * ApplyPatch 工具 - 用于以原子方式应用多文件补丁。
 *
 * <p>
 * 支持 OpenAI Codex DSL 格式的补丁，包含 Add/Update/Move/Delete 操作。
 * 采用两阶段执行：阶段1解析并验证所有 hunk 锚点，阶段2执行实际写入/删除。
 * 任何 hunk 锚定失败都会导致整个操作回滚，不会修改任何文件。
 * 所有路径均通过 {@link WorkspacePaths} 校验，限制在会话 workspace 内。
 * </p>
 */
@Component
public class ApplyPatchTool extends AbstractFileTool {

    private static final String DESCRIPTION =
            """
            Apply a multi-file patch in OpenAI Codex DSL format. Use this instead of chained \
            edit_file/write_file calls when a change touches >=2 files or >=2 hunks — one tool \
            call performs every edit atomically (parse + hunk matching happens for every file \
            before any write; if any hunk fails to anchor, NO file is modified).

            Format:

              *** Begin Patch
              *** Add File: path/new.txt
              +line one
              +line two
              *** Update File: path/old.txt
              *** Move to: path/renamed.txt    (optional rename, before any hunk)
              @@
               keep_this_line
              -drop_this
              +add_this
               keep_this_too
              *** End of File                  (optional; pin the previous hunk to file end)
              *** Delete File: path/legacy.txt
              *** End Patch

            Rules:
            - Hunks anchor on context lines (' ' prefix) plus '-' lines that must literally match \
            the file. Provide enough context to make the location unambiguous; matching is \
            in-order, first match wins.
            - Pure-add hunks (only '+' lines) only work with *** End of File or at the very top of \
            a file.
            - All paths are relative to your workspace.""";

    public ApplyPatchTool(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor(
                "apply_patch",
                DESCRIPTION,
                Map.of(
                        "type", "object",
                        "properties", Map.of("input", Map.of(
                                "type", "string",
                                "description",
                                "The complete patch envelope from `*** Begin Patch` to `*** End Patch`.")),
                        "required", List.of("input")),
                ToolDescriptor.Source.BUILTIN);
    }

    @Override
    protected ToolResult run(JsonNode args, ToolExecutionContext context) throws IOException {
        String input = requiredText(args, "input");
        if (input == null) {
            return missingArgument("input");
        }
        try {
            // 阶段 1：解析 + 全部 hunk 在内存中锚定（读取经路径校验）
            PatchEngine.Plan plan = PatchEngine.plan(input, path -> readWorkspaceFile(context, path));
            // 阶段 2：全部成功后落盘（写与删的目标路径同样校验）
            for (PatchEngine.PlannedWrite write : plan.writes()) {
                Path target = WorkspacePaths.resolve(context.workspace(), write.path());
                Files.createDirectories(target.getParent());
                Files.write(target, write.content().getBytes(StandardCharsets.UTF_8));
            }
            for (String delete : plan.deletes()) {
                Path target = WorkspacePaths.resolve(context.workspace(), delete);
                Files.deleteIfExists(target);
            }
            return ToolResult.success(PatchEngine.summary(plan.ops()));
        } catch (PatchEngine.PatchException error) {
            return ToolResult.failure(ToolErrorCode.TOOL_EXECUTION_FAILED, error.getMessage());
        } catch (UncheckedIOException error) {
            return ToolResult.failure(
                    ToolErrorCode.FILE_NOT_FOUND, "apply_patch: " + error.getCause().getMessage());
        }
    }

    private String readWorkspaceFile(ToolExecutionContext context, String path) {
        Path target = WorkspacePaths.resolve(context.workspace(), path);
        try {
            byte[] data = Files.readAllBytes(target);
            return new String(data, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException(
                    new IOException("read " + path + ": " + error.getClass().getSimpleName()));
        }
    }
}
