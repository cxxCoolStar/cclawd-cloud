package ai.openagent.bootstrap.tool.websearch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 网页搜索配置属性，支持多 provider 链式回退
 *
 * <p>
 * 对应 {@code openagent.tools.web-search.*}，示例：
 * <pre>
 * openagent:
 *   tools:
 *     web-search:
 *       order: "searxng,tavily"
 *       searxng-endpoint: "http://127.0.0.1:8888"
 *       tavily-api-key: "tvly-..."
 * </pre>
 * </p>
 *
 * @param order           provider 回退顺序（逗号分隔；内置 searxng/tavily，
 *                        brave/exa 预留）
 * @param searxngEndpoint SearXNG 实例地址（无需 API key；空 = 未配置，
 *                        表示未启用该 provider，链中自动跳过）
 * @param tavilyApiKey    Tavily API key（空 = 未配置，链中跳过）
 * @param tavilyEnabled   Tavily provider 开关（false 时即使配了 key 也跳过）
 */
@ConfigurationProperties(prefix = "openagent.tools.web-search")
public record WebSearchProperties(
        @DefaultValue("searxng,tavily") String order,
        @DefaultValue("") String searxngEndpoint,
        @DefaultValue("") String tavilyApiKey,
        @DefaultValue("true") boolean tavilyEnabled) {}
