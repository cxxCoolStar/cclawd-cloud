package ai.openagent.bootstrap.tool.websearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.openagent.bootstrap.tool.websearch.config.WebSearchProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tavily provider 单测（本地 HttpServer fixture，对照 SearXNG 测试：
 * 请求体形状、results 解析与 count 截断、错误分类、未配置快速失败）
 */
class TavilyWebSearchProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String startFixture(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/search";
    }

    private TavilyWebSearchProvider provider(String endpoint) {
        return new TavilyWebSearchProvider(
                new WebSearchProperties("searxng,tavily", "", "tvly-test", true), objectMapper, endpoint);
    }

    @Test
    void sendsApiKeyQueryAndMaxResults() throws Exception {
        TavilyWebSearchProvider provider = provider(startFixture(200, "{\"results\": []}"));
        assertTrue(provider.configured());
        provider.search("hello", 3);
        JsonNode sent = objectMapper.readTree(lastRequestBody.get());
        assertEquals("tvly-test", sent.path("api_key").asText());
        assertEquals("hello", sent.path("query").asText());
        assertEquals(3, sent.path("max_results").asInt());
    }

    @Test
    void parsesResultsAndTruncatesToCount() throws Exception {
        String body = """
                {"results": [
                  {"title": "T1", "url": "https://a", "content": "c1"},
                  {"title": "T2", "url": "https://b", "content": "c2"},
                  {"title": "T3", "url": "https://c", "content": "c3"}
                ]}
                """;
        TavilyWebSearchProvider provider = provider(startFixture(200, body));
        var items = provider.search("q", 2);
        assertEquals(2, items.size());
        assertEquals("T1", items.get(0).title());
        assertEquals("https://b", items.get(1).url());
        assertEquals("c2", items.get(1).snippet());
    }

    @Test
    void http429IsRetriable() throws Exception {
        TavilyWebSearchProvider provider = provider(startFixture(429, "{}"));
        WebSearchException error = assertThrows(WebSearchException.class, () -> provider.search("q", 5));
        assertTrue(error.retriable());
    }

    @Test
    void http500IsRetriable() throws Exception {
        TavilyWebSearchProvider provider = provider(startFixture(500, "{}"));
        WebSearchException error = assertThrows(WebSearchException.class, () -> provider.search("q", 5));
        assertTrue(error.retriable());
    }

    @Test
    void http401IsNotRetriable() throws Exception {
        TavilyWebSearchProvider provider = provider(startFixture(401, "{}"));
        WebSearchException error = assertThrows(WebSearchException.class, () -> provider.search("q", 5));
        assertTrue(!error.retriable());
    }

    @Test
    void unreachableEndpointIsRetriable() {
        TavilyWebSearchProvider provider = provider("http://127.0.0.1:1/search");
        WebSearchException error = assertThrows(WebSearchException.class, () -> provider.search("q", 5));
        assertTrue(error.retriable());
    }

    @Test
    void blankApiKeyIsNotConfigured() {
        TavilyWebSearchProvider provider = new TavilyWebSearchProvider(
                new WebSearchProperties("searxng,tavily", "", "", true), objectMapper, "http://127.0.0.1:1/search");
        assertTrue(!provider.configured());
        WebSearchException error = assertThrows(WebSearchException.class, () -> provider.search("q", 5));
        assertTrue(!error.retriable());
    }

    @Test
    void disabledProviderIsNotConfigured() {
        TavilyWebSearchProvider provider = new TavilyWebSearchProvider(
                new WebSearchProperties("searxng,tavily", "", "tvly-test", false),
                objectMapper,
                "http://127.0.0.1:1/search");
        assertTrue(!provider.configured());
    }
}
