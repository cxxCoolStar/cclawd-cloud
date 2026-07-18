package ai.openagent.bootstrap.tool.websearch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 网页搜索配置属性（对照 fastclaw toolproviders 的链配置）
 *
 * <p>
 * 对应 {@code openagent.tools.web-search.*}，示例：
 * <pre>
 * openagent:
 *   tools:
 *     web-search:
 *       order: "searxng"
 *       searxng-endpoint: "http://127.0.0.1:8888"
 * </pre>
 * </p>
 *
 * @param order           provider 回退顺序（逗号分隔；当前仅内置 searxng，
 *                        brave/exa 预留）
 * @param searxngEndpoint SearXNG 实例地址（无需 API key；空 = 未配置，
 *                        对应 fastclaw「无凭证即隐藏」——链不可用）
 */
@ConfigurationProperties(prefix = "openagent.tools.web-search")
public record WebSearchProperties(
        @DefaultValue("searxng") String order,
        @DefaultValue("") String searxngEndpoint) {}
