package ai.openagent.bootstrap.chat;

import ai.openagent.bootstrap.persistence.AgentRecord;
import ai.openagent.bootstrap.persistence.ChatMessageRecord;
import ai.openagent.bootstrap.persistence.ProviderRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

@Component
public class OpenAiCompatibleChatModelGateway implements ChatModelGateway {

    private final ObjectMapper objectMapper;

    public OpenAiCompatibleChatModelGateway(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String stream(
            ProviderRecord provider,
            AgentRecord agent,
            List<ChatMessageRecord> messages,
            Consumer<String> onDelta)
            throws Exception {
        if (provider.apiKey() == null || provider.apiKey().isBlank()) {
            throw new IllegalStateException("OPENAGENT_MODEL_API_KEY is not configured");
        }

        List<Map<String, String>> requestMessages = new ArrayList<>();
        requestMessages.add(Map.of("role", "system", "content", agent.systemPrompt()));
        for (ChatMessageRecord message : messages) {
            if ("user".equals(message.role()) || "assistant".equals(message.role())) {
                requestMessages.add(Map.of("role", message.role(), "content", message.content()));
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", agent.model());
        body.put("messages", requestMessages);
        body.put("stream", true);
        body.put("temperature", provider.temperature());
        body.put("max_tokens", provider.maxTokens());

        HttpRequest request = HttpRequest.newBuilder(chatCompletionsUri(provider.apiBase()))
                .timeout(Duration.ofMinutes(10))
                .header("Authorization", "Bearer " + provider.apiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        HttpResponse<java.io.InputStream> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String error = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new IllegalStateException("model request failed with HTTP " + response.statusCode() + ": " + error);
        }

        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) {
                    continue;
                }
                JsonNode root = objectMapper.readTree(data);
                JsonNode choices = root.path("choices");
                if (!choices.isArray() || choices.isEmpty()) {
                    continue;
                }
                JsonNode content = choices.get(0).path("delta").path("content");
                if (content.isTextual() && !content.textValue().isEmpty()) {
                    result.append(content.textValue());
                    onDelta.accept(content.textValue());
                }
            }
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("model returned an empty response");
        }
        return result.toString();
    }

    private URI chatCompletionsUri(String apiBase) {
        String normalized = apiBase == null ? "" : apiBase.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (!normalized.endsWith("/chat/completions")) {
            normalized += "/chat/completions";
        }
        return URI.create(normalized);
    }
}
