package ai.openagent.bootstrap.tool.websearch;

import ai.openagent.bootstrap.tool.websearch.config.WebSearchProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * SearXNG Web 搜索 provider，通过自建 SearXNG 实例提供搜索服务。
 *
 * <p>
 * 无需 API key，只需自建实例 endpoint：GET {endpoint}/search?q=&format=json，
 * 必须带 UA（空 UA 会被 SearXNG 拒绝）；15 秒超时；429/5xx/网络错误
 * 标记 retriable 供链回退
 * </p>
 */
@Component
public class SearxNgWebSearchProvider implements WebSearchProvider {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final String USER_AGENT = "openagent/1.0";

    private final WebSearchProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public SearxNgWebSearchProvider(WebSearchProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String name() {
        return "searxng";
    }

    @Override
    public boolean configured() {
        return !properties.searxngEndpoint().isBlank();
    }

    @Override
    public List<ResultItem> search(String query, int count) {
        String endpoint = properties.searxngEndpoint().replaceAll("/+$", "");
        if (endpoint.isEmpty()) {
            throw new WebSearchException("searxng: missing endpoint", false);
        }
        String url = endpoint + "/search?q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&format=json";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException error) {
            throw new WebSearchException("searxng request failed: " + error.getMessage(), true, error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new WebSearchException("searxng request interrupted", true, error);
        }
        int status = response.statusCode();
        if (status != 200) {
            try (InputStream ignored = response.body()) {
                // 排空响应体
            } catch (IOException ignored) {
                // 忽略
            }
            boolean retriable = status == 429 || status >= 500;
            throw new WebSearchException("searxng HTTP " + status, retriable);
        }
        try (InputStream body = response.body()) {
            JsonNode root = objectMapper.readTree(body);
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
            throw new WebSearchException("searxng decode failed: " + error.getMessage(), false, error);
        }
    }
}
