package ai.openagent.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openagent.model")
public record ModelSettings(
        String provider,
        String apiBase,
        String apiKey,
        String name,
        double temperature,
        int maxTokens,
        String systemPrompt) {

    public boolean ready() {
        return apiKey != null && !apiKey.isBlank();
    }
}
