package ai.openagent.bootstrap.tool.adapter;

import ai.openagent.agent.tool.ToolDescriptor;
import ai.openagent.agent.tool.ToolErrorCode;
import ai.openagent.agent.tool.ToolExecutionContext;
import ai.openagent.agent.tool.ToolResult;
import ai.openagent.bootstrap.tool.config.ToolProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * web_fetch 工具 - 安全地获取网页内容并返回纯文本
 *
 * <p>
 * 安全规则（V2 方案 12.2）：仅 http/https；DNS 解析后逐地址校验，
 * 拒绝 loopback / private / link-local / multicast / CGNAT / 云元数据段；
 * 重定向不自动跟随——每一跳的目标重新走完整校验（Java HttpClient 使用
 * NEVER redirect + 手动逐跳，等价关闭 DNS 重绑定的 TOCTOU 窗口收窄为单跳内）；
 * 限制重定向次数、响应大小与总耗时；不携带凭证；HTML 粗略去标签后作为不可信文本返回
 * </p>
 */
@Component
public class WebFetchTool extends AbstractFileTool {

    private static final int MAX_REDIRECTS = 5;
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(30);
    private static final String USER_AGENT = "OpenAgent/1.0 (AI Agent Web Fetcher)";
    private static final int DEFAULT_MAX_LEN = 10000;

    private final ToolProperties toolProperties;
    private final HttpClient httpClient;

    public WebFetchTool(ObjectMapper objectMapper, ToolProperties toolProperties) {
        super(objectMapper);
        this.toolProperties = toolProperties;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor(
                "web_fetch",
                "Fetch a single known URL and return its plain text. Use this only after you "
                        + "already know the exact target page URL. If the user's message itself contains "
                        + "a URL or bare domain, fetch THAT URL directly — prepend https:// for bare "
                        + "domains. DO NOT guess URLs from memory: your training data has stale paths "
                        + "and you will burn rounds on 404s. Prefer well-known stable hosts.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "url", Map.of(
                                        "type", "string",
                                        "description", "The exact URL to fetch (full https://... form)."),
                                "max_length", Map.of(
                                        "type", "integer",
                                        "description", "Maximum characters to return (default 10000)")),
                        "required", List.of("url")),
                ToolDescriptor.Source.BUILTIN);
    }

    @Override
    protected ToolResult run(JsonNode args, ToolExecutionContext context) throws IOException {
        // 双重门控（V2 方案 3.1）：agent_tools 启停之外，全局配置
        // openagent.tools.web-fetch-enabled 也必须显式打开
        if (!toolProperties.webFetchEnabled()) {
            return ToolResult.failure(
                    ToolErrorCode.TOOL_NOT_ENABLED,
                    "web_fetch is disabled by server configuration (OPENAGENT_WEB_FETCH_ENABLED)");
        }
        String url = requiredText(args, "url");
        if (url == null) {
            return missingArgument("url");
        }
        int maxLen = args.path("max_length").asInt(DEFAULT_MAX_LEN);
        if (maxLen <= 0) {
            maxLen = DEFAULT_MAX_LEN;
        }
        long deadline = System.currentTimeMillis() + FETCH_TIMEOUT.toMillis();

        String current = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            URI uri;
            try {
                uri = URI.create(current);
            } catch (IllegalArgumentException error) {
                return ToolResult.failure(ToolErrorCode.TOOL_ARGUMENT_INVALID, "invalid url: " + current);
            }
            ToolResult blocked = assertTargetAllowed(uri);
            if (blocked != null) {
                return blocked;
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return ToolResult.failure(ToolErrorCode.TOOL_TIMEOUT, "fetch timed out");
            }
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(remaining))
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            HttpResponse<InputStream> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return ToolResult.failure(ToolErrorCode.TOOL_EXECUTION_FAILED, "fetch interrupted");
            }
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                try (InputStream ignored = response.body()) {
                    // 排空并关闭重定向响应体
                }
                String location = response.headers().firstValue("Location").orElse(null);
                if (location == null) {
                    return ToolResult.failure(
                            ToolErrorCode.TOOL_EXECUTION_FAILED, "HTTP " + status + " without Location");
                }
                // 每一跳重新完整校验（方案 12.2：每次重定向后重新校验目标）
                current = uri.resolve(location).toString();
                continue;
            }
            if (status != 200) {
                try (InputStream ignored = response.body()) {
                    // 关闭错误响应体
                }
                return ToolResult.failure(ToolErrorCode.TOOL_EXECUTION_FAILED, "HTTP " + status);
            }
            byte[] body;
            try (InputStream in = response.body()) {
                body = in.readNBytes((int) Math.min(toolProperties.webFetchMaxBytes(), Integer.MAX_VALUE));
            }
            String text = stripHtml(new String(body, StandardCharsets.UTF_8));
            if (text.length() > maxLen) {
                text = text.substring(0, maxLen) + "\n[...truncated]";
            }
            return ToolResult.success(text);
        }
        return ToolResult.failure(ToolErrorCode.TOOL_EXECUTION_FAILED, "too many redirects");
    }

    /**
     * 目标校验：scheme 白名单 + DNS 解析后逐地址封禁检查。
     * 返回 null 表示允许
     */
    private ToolResult assertTargetAllowed(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return ToolResult.failure(
                    ToolErrorCode.NETWORK_TARGET_FORBIDDEN,
                    "scheme \"" + scheme + "\" not allowed; use http or https");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return ToolResult.failure(ToolErrorCode.TOOL_ARGUMENT_INVALID, "url has no host");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException error) {
            return ToolResult.failure(ToolErrorCode.TOOL_EXECUTION_FAILED, "could not resolve host: " + host);
        }
        for (InetAddress address : addresses) {
            if (isBlockedAddress(address)) {
                return ToolResult.failure(
                        ToolErrorCode.NETWORK_TARGET_FORBIDDEN,
                        "blocked address for host " + host + " (private/loopback/metadata targets are forbidden)");
            }
        }
        return null;
    }

    /**
     * 封禁地址分类：loopback、link-local、multicast、unspecified、
     * RFC1918/ULA 私网、CGNAT 100.64/10、169.254/16
     */
    static boolean isBlockedAddress(InetAddress address) {
        if (address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isMulticastAddress()
                || address.isAnyLocalAddress()
                || address.isSiteLocalAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int b0 = bytes[0] & 0xff;
            int b1 = bytes[1] & 0xff;
            // CGNAT 100.64.0.0/10
            if (b0 == 100 && (b1 & 0xc0) == 0x40) {
                return true;
            }
            // 169.254/16（isLinkLocalAddress 已覆盖，显式列出以保持一致性）
            if (b0 == 169 && b1 == 254) {
                return true;
            }
        }
        if (bytes.length == 16) {
            // IPv6 ULA fc00::/7（isSiteLocalAddress 只覆盖已废弃的 fec0::/10）
            int b0 = bytes[0] & 0xff;
            if ((b0 & 0xfe) == 0xfc) {
                return true;
            }
        }
        return false;
    }

    /**
     * 粗略 HTML 去标签（script/style 整块移除 → 标签剥离 → 实体还原 →
     * 空白折叠）。V2 不引入解析库，响应只作为不可信文本交给模型
     */
    static String stripHtml(String html) {
        String text = html
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
        return text.replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }
}
