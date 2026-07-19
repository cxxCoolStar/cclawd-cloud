package ai.openagent.bootstrap.tool.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.openagent.agent.tool.ToolArguments;
import ai.openagent.agent.tool.ToolErrorCode;
import ai.openagent.agent.tool.ToolExecutionContext;
import ai.openagent.agent.tool.ToolResult;
import ai.openagent.bootstrap.tool.websearch.WebSearchProvider;
import ai.openagent.bootstrap.tool.websearch.config.WebSearchProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * web_search 工具单测：门控（无配置即不可用）、参数校验、渲染输出
 */
class WebSearchToolTest {

    private static ToolExecutionContext context() {
        return new ToolExecutionContext(
                "run-1", "local-user", "default", "s1",
                Path.of("target/web-search-ws"), Instant.now().plusSeconds(60));
    }

    private static WebSearchProvider stubProvider() {
        return new WebSearchProvider() {
            @Override
            public String name() {
                return "searxng";
            }

            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public List<ResultItem> search(String query, int count) {
                return List.of(new ResultItem("T1", "https://a", "c1"));
            }
        };
    }

    private static WebSearchTool tool(String endpoint, List<WebSearchProvider> providers) {
        return new WebSearchTool(
                new ObjectMapper(), new WebSearchProperties("searxng", endpoint, "", true), providers);
    }

    @Test
    void rejectsWhenNoProviderConfigured() {
        // 属性里 endpoint 为空且 stub provider 也 configured=false → 链不可用
        WebSearchTool tool = tool("", List.of());
        ToolResult result = tool.execute(new ToolArguments("{\"query\":\"x\"}"), context());
        assertFalse(result.success());
        assertEquals(ToolErrorCode.TOOL_NOT_ENABLED, result.errorCode());
    }

    @Test
    void rejectsMissingQuery() {
        WebSearchTool tool = tool("http://x", List.of(stubProvider()));
        ToolResult result = tool.execute(new ToolArguments("{}"), context());
        assertFalse(result.success());
        assertEquals(ToolErrorCode.TOOL_ARGUMENT_INVALID, result.errorCode());
    }

    @Test
    void rendersResults() {
        WebSearchTool tool = tool("http://x", List.of(stubProvider()));
        ToolResult result = tool.execute(new ToolArguments("{\"query\":\"openai\",\"count\":5}"), context());
        assertTrue(result.success());
        assertTrue(result.content().startsWith("Search results for: openai"));
        assertTrue(result.content().contains("1. T1"));
    }
}
