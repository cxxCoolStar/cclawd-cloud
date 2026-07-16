package ai.openagent.bootstrap.chat.gateway;

import ai.openagent.bootstrap.persistence.AgentRecord;
import ai.openagent.bootstrap.persistence.ChatMessageRecord;
import ai.openagent.bootstrap.persistence.ProviderRecord;
import ai.openagent.framework.errorcode.BaseErrorCode;
import ai.openagent.framework.exception.RemoteException;
import ai.openagent.framework.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStream;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * OpenAI 兼容协议的聊天模型网关
 *
 * <p>
 * 通过 {@code /chat/completions} SSE 流式接口调用任意 OpenAI 兼容供应商。
 * HttpClient 为单例复用；请求构建、SSE 行解析拆分为独立方法
 * </p>
 */
@Slf4j
@Component
public class OpenAiCompatibleChatModelGateway implements ChatModelGateway {

    /**
     * 整体请求超时：覆盖模型长回答的流式全程
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(10);

    /**
     * TCP 连接建立超时
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenAiCompatibleChatModelGateway(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    @Override
    public String stream(
            ProviderRecord provider,
            AgentRecord agent,
            List<ChatMessageRecord> messages,
            Consumer<String> onDelta)
            throws Exception {
        if (provider.apiKey() == null || provider.apiKey().isBlank()) {
            throw new ServiceException(
                    "OPENAGENT_MODEL_API_KEY is not configured", BaseErrorCode.SERVICE_UNAVAILABLE_ERROR);
        }

        HttpRequest request = buildRequest(provider, agent, messages);
        long startedAt = System.currentTimeMillis();
        log.info("[gateway] 调用模型，provider={}, model={}, messages={}",
                provider.type(), agent.model(), messages.size());

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String error = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            log.error("[gateway] 模型请求失败，HTTP {}，耗时 {}ms：{}",
                    response.statusCode(), System.currentTimeMillis() - startedAt, error);
            throw new RemoteException(
                    "model request failed with HTTP " + response.statusCode() + ": " + error,
                    BaseErrorCode.MODEL_INVOKE_ERROR);
        }

        String answer = readSseStream(response.body(), onDelta);
        if (answer.isEmpty()) {
            throw new RemoteException("model returned an empty response", BaseErrorCode.MODEL_INVOKE_ERROR);
        }
        log.info("[gateway] 模型调用完成，model={}, 回答长度={}, 耗时 {}ms",
                agent.model(), answer.length(), System.currentTimeMillis() - startedAt);
        return answer;
    }

    /**
     * 构建 OpenAI 兼容的 chat/completions 流式请求
     */
    private HttpRequest buildRequest(
            ProviderRecord provider, AgentRecord agent, List<ChatMessageRecord> messages) throws Exception {
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

        return HttpRequest.newBuilder(chatCompletionsUri(provider.apiBase()))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + provider.apiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
    }

    /**
     * 逐行解析 SSE 响应流，回调增量文本并拼接完整回答
     */
    private String readSseStream(InputStream body, Consumer<String> onDelta) throws Exception {
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
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
