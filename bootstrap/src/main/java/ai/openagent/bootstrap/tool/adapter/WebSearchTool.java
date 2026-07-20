package ai.openagent.bootstrap.tool.adapter;

import ai.openagent.agent.tool.ToolDescriptor;
import ai.openagent.agent.tool.ToolErrorCode;
import ai.openagent.agent.tool.ToolExecutionContext;
import ai.openagent.agent.tool.ToolResult;
import ai.openagent.bootstrap.tool.websearch.WebSearchChain;
import ai.openagent.bootstrap.tool.websearch.WebSearchException;
import ai.openagent.bootstrap.tool.websearch.WebSearchProvider;
import ai.openagent.bootstrap.tool.websearch.WebSearchResultRenderer;
import ai.openagent.bootstrap.tool.websearch.config.WebSearchProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * web_search 工具
 *
 * <p>
 * 一个工具背后是一条 provider 回退链（当前内置 SearXNG，brave/exa 预留）。
 * 「无配置即隐藏」语义的双重门控：agent_tools 启用之外，链必须至少有一个
 * 已配置 provider（如 openagent.tools.web-search.searxng-endpoint）
 * </p>
 */
@Component
public class WebSearchTool extends AbstractFileTool {

    private static final int DEFAULT_COUNT = 5;
    private static final int MAX_COUNT = 20;

    private final WebSearchChain chain;

    public WebSearchTool(
            ObjectMapper objectMapper, WebSearchProperties properties, List<WebSearchProvider> providers) {
        super(objectMapper);
        this.chain = WebSearchChain.of(properties.order(), providers);
    }

    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor(
                "web_search",
                "Search the web and return results with titles, URLs, and snippets. Use this "
                        + "when the user asks about current events, facts you're unsure of, or "
                        + "anything that needs up-to-date information. Backed by a configurable "
                        + "provider chain with automatic fallback.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of(
                                        "type", "string",
                                        "description", "The search query"),
                                "count", Map.of(
                                        "type", "integer",
                                        "description", "Number of results to return (default 5, max 20)")),
                        "required", List.of("query")),
                ToolDescriptor.Source.BUILTIN);
    }

    @Override
    protected ToolResult run(JsonNode args, ToolExecutionContext context) {
        if (!chain.available()) {
            return ToolResult.failure(
                    ToolErrorCode.TOOL_NOT_ENABLED,
                    "web_search is not configured (set OPENAGENT_WEB_SEARCH_SEARXNG_ENDPOINT)");
        }
        String query = requiredText(args, "query");
        if (query == null) {
            return missingArgument("query");
        }
        int count = args.path("count").asInt(DEFAULT_COUNT);
        if (count <= 0) {
            count = DEFAULT_COUNT;
        }
        count = Math.min(count, MAX_COUNT);
        try {
            List<WebSearchProvider.ResultItem> items = chain.search(query, count);
            return ToolResult.success(WebSearchResultRenderer.render(query, items));
        } catch (WebSearchException error) {
            return ToolResult.failure(ToolErrorCode.TOOL_EXECUTION_FAILED, error.getMessage());
        }
    }
}
