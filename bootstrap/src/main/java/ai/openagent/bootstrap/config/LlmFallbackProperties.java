package ai.openagent.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * LLM 主备切换配置属性（V8 方案 3.1）
 *
 * <p>
 * 对应 {@code openagent.llm.fallback.*}，示例：
 * <pre>
 * openagent:
 *   llm:
 *     fallback:
 *       base-url: "https://api.deepseek.com"
 *       api-key: "sk-..."
 *       model: "deepseek-v4-pro"
 * </pre>
 * base-url/api-key 任一为空即未配置，{@code FallbackLLMService} 直通主
 * provider；model 为空时重试沿用主请求的 model
 * </p>
 *
 * @param baseUrl 备用 provider 的 OpenAI 兼容 API 地址
 * @param apiKey  备用 provider 的 API key
 * @param model   备用 provider 的模型（空 = 沿用主请求模型）
 */
@ConfigurationProperties(prefix = "openagent.llm.fallback")
public record LlmFallbackProperties(
        @DefaultValue("") String baseUrl,
        @DefaultValue("") String apiKey,
        @DefaultValue("") String model) {}
