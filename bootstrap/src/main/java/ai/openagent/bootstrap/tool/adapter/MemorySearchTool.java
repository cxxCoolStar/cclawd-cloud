package ai.openagent.bootstrap.tool.adapter;

import ai.openagent.agent.tool.ToolDescriptor;
import ai.openagent.agent.tool.ToolErrorCode;
import ai.openagent.agent.tool.ToolExecutionContext;
import ai.openagent.agent.tool.ToolResult;
import ai.openagent.bootstrap.memory.MemoryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * memory_search 工具（V3 方案 M2）
 *
 * <p>
 * 在 agent 级记忆文件（MEMORY.md / USER.md / HISTORY.md）中做文本检索，
 * 只读、默认启用。检索对象是长期记忆而非会话 workspace 文件，
 * 因此读取路径走 {@link MemoryService}（agent 级目录），与
 * 会话 workspace 隔离的文件工具族互不影响
 * </p>
 */
@Component
public class MemorySearchTool extends AbstractFileTool {

    private static final int DEFAULT_MAX_HITS = 20;
    private static final int MAX_HITS_CAP = 50;

    private final MemoryService memoryService;

    public MemorySearchTool(ObjectMapper objectMapper, MemoryService memoryService) {
        super(objectMapper);
        this.memoryService = memoryService;
    }

    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor(
                "memory_search",
                "Search the agent's long-term memory files (MEMORY.md, USER.md, HISTORY.md) "
                        + "for lines matching a query. Use this to recall user preferences, "
                        + "past decisions, or facts from earlier sessions.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of(
                                        "type", "string",
                                        "description", "Text to search for (case-insensitive substring match)"),
                                "max_hits", Map.of(
                                        "type", "integer",
                                        "description", "Maximum matching lines to return (default 20, max 50)")),
                        "required", List.of("query")),
                ToolDescriptor.Source.BUILTIN);
    }

    @Override
    protected ToolResult run(JsonNode args, ToolExecutionContext context) {
        if (!memoryService.enabled()) {
            return ToolResult.failure(
                    ToolErrorCode.TOOL_NOT_ENABLED, "memory is disabled by server configuration");
        }
        String query = requiredText(args, "query");
        if (query == null) {
            return missingArgument("query");
        }
        int maxHits = args.path("max_hits").asInt(DEFAULT_MAX_HITS);
        if (maxHits <= 0) {
            maxHits = DEFAULT_MAX_HITS;
        }
        maxHits = Math.min(maxHits, MAX_HITS_CAP);

        List<String> hits = memoryService.search(context.agentId(), context.conversationScope(), query, maxHits);
        if (hits.isEmpty()) {
            return ToolResult.success("No memory entries matching \"" + query + "\".");
        }
        return ToolResult.success(String.join("\n", hits));
    }
}
