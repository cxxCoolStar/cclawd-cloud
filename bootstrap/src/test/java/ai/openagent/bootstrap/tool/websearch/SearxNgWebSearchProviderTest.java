package ai.openagent.bootstrap.tool.websearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.openagent.bootstrap.tool.websearch.config.WebSearchProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * SearXNG provider 单测（本地 HttpServer fixture，对照 fastclaw
 * searxng.go 行为：JSON 解析、count 截断、错误分类）
 */
class SearxNgWebSearchProviderTest {

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
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private SearxNgWebSearchProvider provider(String endpoint) {
        return new SearxNgWebSearchProvider(new WebSearchProperties("searxng", endpoint), new ObjectMapper());
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
        SearxNgWebSearchProvider provider = provider(startFixture(200, body));
        assertTrue(provider.configured());
        var items = provider.search("q", 2);
        assertEquals(2, items.size());
        assertEquals("T1", items.get(0).title());
        assertEquals("c2", items.get(1).snippet());
    }

    @Test
    void http429IsRetriable() throws Exception {
        SearxNgWebSearchProvider provider = provider(startFixture(429, "{}"));
        WebSearchException error = assertThrows(WebSearchException.class, () -> provider.search("q", 5));
        assertTrue(error.retriable());
    }

    @Test
    void http404IsNotRetriable() throws Exception {
        SearxNgWebSearchProvider provider = provider(startFixture(404, "{}"));
        WebSearchException error = assertThrows(WebSearchException.class, () -> provider.search("q", 5));
        assertTrue(!error.retriable());
    }

    @Test
    void unreachableEndpointIsRetriable() {
        SearxNgWebSearchProvider provider = provider("http://127.0.0.1:1");
        WebSearchException error = assertThrows(WebSearchException.class, () -> provider.search("q", 5));
        assertTrue(error.retriable());
    }

    @Test
    void blankEndpointIsNotConfigured() {
        assertTrue(!provider("").configured());
    }
}
