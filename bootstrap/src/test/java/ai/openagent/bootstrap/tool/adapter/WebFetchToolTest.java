package ai.openagent.bootstrap.tool.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.openagent.agent.tool.ToolArguments;
import ai.openagent.agent.tool.ToolErrorCode;
import ai.openagent.agent.tool.ToolExecutionContext;
import ai.openagent.agent.tool.ToolResult;
import ai.openagent.bootstrap.tool.config.ToolProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * web_fetch SSRF 防护测试（V2 方案 16.1：SSRF 地址分类；12.2 网络访问规则）
 */
class WebFetchToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ToolProperties props(boolean webFetchEnabled) {
        return new ToolProperties(
                Duration.ofSeconds(30), 65536, "./workspace", 1024 * 1024, webFetchEnabled, 1024 * 1024);
    }

    private static ToolExecutionContext context() {
        return new ToolExecutionContext(
                "run-1", "local-user", "default", "s1", Path.of("target/ws"), Instant.now().plusSeconds(30));
    }

    private static ToolResult fetch(boolean enabled, String url) {
        return new WebFetchTool(MAPPER, props(enabled))
                .execute(new ToolArguments("{\"url\":\"" + url + "\"}"), context());
    }

    // ==================== 地址分类（isBlockedAddress） ====================

    @Test
    void blockedAddressClassification() throws Exception {
        String[] blocked = {
            "127.0.0.1",        // loopback
            "10.0.0.1",         // RFC1918
            "172.16.0.1",       // RFC1918
            "192.168.1.1",      // RFC1918
            "169.254.169.254",  // 云元数据
            "100.64.0.1",       // CGNAT
            "0.0.0.0",          // unspecified
            "224.0.0.1",        // multicast
            "::1",              // IPv6 loopback
            "fe80::1",          // IPv6 link-local
            "fc00::1",          // IPv6 ULA
            "fd12:3456::1",     // IPv6 ULA
        };
        for (String ip : blocked) {
            assertTrue(WebFetchTool.isBlockedAddress(InetAddress.getByName(ip)), "应封禁: " + ip);
        }
        String[] allowed = {"8.8.8.8", "1.1.1.1", "104.16.132.229", "2606:4700::6810:84e5"};
        for (String ip : allowed) {
            assertFalse(WebFetchTool.isBlockedAddress(InetAddress.getByName(ip)), "不应封禁: " + ip);
        }
    }

    // ==================== 工具级防线 ====================

    @Test
    void disabledByConfigReturnsToolNotEnabled() {
        ToolResult result = fetch(false, "https://example.com/");
        assertFalse(result.success());
        assertEquals(ToolErrorCode.TOOL_NOT_ENABLED, result.errorCode());
    }

    @Test
    void nonHttpSchemesAreForbidden() {
        for (String url : new String[] {"file:///etc/passwd", "ftp://example.com/x", "gopher://example.com/"}) {
            ToolResult result = fetch(true, url);
            assertFalse(result.success(), "应拒绝: " + url);
            assertEquals(ToolErrorCode.NETWORK_TARGET_FORBIDDEN, result.errorCode(), url);
        }
    }

    @Test
    void loopbackAndMetadataTargetsAreForbidden() {
        for (String url : new String[] {
            "http://127.0.0.1:18953/api/agents",
            "http://localhost:8080/",
            "http://169.254.169.254/latest/meta-data/",
            "http://192.168.1.1/admin",
        }) {
            ToolResult result = fetch(true, url);
            assertFalse(result.success(), "应拒绝: " + url);
            assertEquals(ToolErrorCode.NETWORK_TARGET_FORBIDDEN, result.errorCode(), url);
        }
    }

    // ==================== HTML 去标签 ====================

    @Test
    void stripHtmlRemovesTagsAndScripts() {
        String html = "<html><head><script>evil()</script><style>.x{}</style></head>"
                + "<body><h1>Title</h1><p>Hello &amp; world</p></body></html>";
        String text = WebFetchTool.stripHtml(html);
        assertFalse(text.contains("evil"));
        assertFalse(text.contains("<"));
        assertTrue(text.contains("Title"));
        assertTrue(text.contains("Hello & world"));
    }
}
