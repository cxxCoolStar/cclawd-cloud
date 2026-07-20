package ai.openagent.bootstrap.tool.websearch;

import ai.openagent.bootstrap.tool.websearch.config.WebSearchProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Tavily 搜索 provider，基于 Tavily API 提供托管搜索服务
 *
 * <p>
 * 托管服务，需 API key：POST https://api.tavily.com/search，
 * 请求体 {api_key, query, max_results}，响应 results[{title,url,content}]；
 * 15 秒超时；429/5xx/网络错误标记 retriable 供链回退，4xx 参数/凭证
 * 错误快速失败（配置 bug 不被回退掩盖）。作为 SearXNG 之后的链上
 * 备用 provider（默认 order = "searxng,tavily"）
 * </p>
 */
@Component
public class TavilyWebSearchProvider implements WebSearchProvider {

    private static final String SEARCH_URL = "https://api.tavily.com/search";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final WebSearchProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String searchUrl;

    @Autowired
    public TavilyWebSearchProvider(WebSearchProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, SEARCH_URL);
    }

    /**
     * 测试用构造：端点可指向本地 fixture
     */
    TavilyWebSearchProvider(WebSearchProperties properties, ObjectMapper objectMapper, String searchUrl) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.searchUrl = searchUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String name() {
        return "tavily";
    }

    @Override
    public boolean configured() {
        return properties.tavilyEnabled() && !properties.tavilyApiKey().isBlank();
    }

    @Override
    public List<ResultItem> search(String query, int count) {
        if (!configured()) {
            throw new WebSearchException("tavily: missing api key", false);
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("api_key", properties.tavilyApiKey());
        body.put("query", query);
        body.put("max_results", count);
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(searchUrl))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
        } catch (IOException error) {
            throw new WebSearchException("tavily encode failed: " + error.getMessage(), false, error);
        }
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException error) {
            throw new WebSearchException("tavily request failed: " + error.getMessage(), true, error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new WebSearchException("tavily request interrupted", true, error);
        }
        int status = response.statusCode();
        if (status != 200) {
            try (InputStream ignored = response.body()) {
                // 排空响应体
            } catch (IOException ignored) {
                // 忽略
            }
            boolean retriable = status == 429 || status >= 500;
            throw new WebSearchException("tavily HTTP " + status, retriable);
        }
        try (InputStream responseBody = response.body()) {
            JsonNode root = objectMapper.readTree(responseBody);
            List<ResultItem> items = new ArrayList<>();
            for (JsonNode result : root.path("results")) {
                if (items.size() >= count) {
                    break;
                }
                items.add(new ResultItem(
                        result.path("title").asText(""),
                        result.path("url").asText(""),
                        result.path("content").asText("")));
            }
            return items;
        } catch (IOException error) {
            throw new WebSearchException("tavily decode failed: " + error.getMessage(), false, error);
        }
    }
}
