package ai.openagent.bootstrap.config;

import ai.openagent.infra.ai.FallbackLLMService;
import ai.openagent.infra.ai.LLMService;
import ai.openagent.infra.ai.openai.OpenAiCompatibleLLMService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 模型服务装配（V2 方案 5.1：bootstrap 负责把端口与实现装配起来）
 *
 * <p>
 * infra-ai 不依赖 Spring，OpenAI 兼容实现由此处装配为 Bean；
 * V2 仅一种协议实现，后续多供应商协议再引入按 provider type 分流；
 * V8 起外包 FallbackLLMService：配置 openagent.llm.fallback.* 后主
 * provider 首 delta 前遇 retriable 错误自动切换备用，未配置时直通
 * </p>
 */
@Configuration
public class LLMServiceConfiguration {

    @Bean
    public LLMService llmService(ObjectMapper objectMapper, LlmFallbackProperties fallbackProperties) {
        return new FallbackLLMService(
                new OpenAiCompatibleLLMService(objectMapper),
                fallbackProperties.baseUrl(),
                fallbackProperties.apiKey(),
                fallbackProperties.model());
    }
}
