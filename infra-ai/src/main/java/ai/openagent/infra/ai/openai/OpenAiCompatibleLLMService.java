package ai.openagent.infra.ai.openai;

import ai.openagent.infra.ai.LLMService;
import ai.openagent.infra.ai.ModelEventListener;
import ai.openagent.infra.ai.model.ModelEvent;
import ai.openagent.infra.ai.model.ModelMessage;
import ai.openagent.infra.ai.model.ModelRequest;
import ai.openagent.infra.ai.model.ModelResponse;
import ai.openagent.infra.ai.model.TokenUsage;
import ai.openagent.infra.ai.model.ToolCall;
import ai.openagent.infra.ai.model.ToolDefinition;
import ai.openagent.framework.errorcode.BaseErrorCode;
import ai.openagent.framework.exception.RemoteException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;
import lombok.extern.slf4j.Slf4j;

/**
 * OpenAI 兼容协议的模型服务（V2 方案 M2，迁移自 bootstrap 的
 * OpenAiCompatibleChatModelGateway 并按 fastclaw internal/provider/openai.go
 * 语义重写）
 *
 * <p>
 * 关键行为对齐 fastclaw：
 * <ul>
 *   <li>tools 非空才携带 tools 字段；tool_choice 交由供应商默认（auto）；</li>
 *   <li>流式 tool call 分片按 index 聚合，id/name/arguments 增量合并
 *       （{@link ToolCallAccumulator}）；</li>
 *   <li>非法 SSE chunk 记 WARN 后跳过，不中断整个流；</li>
 *   <li>历史重放时 assistant 消息优先原样回传 rawAssistantJson
 *       （字节一致保证 prompt cache 命中）；孤立 tool_calls 在构造 wire
 *       消息时剥离（openai.go findOrphanToolCalls——孤立 tool call 会使
 *       请求被供应商 400 拒绝）；</li>
 *   <li>stream_options.include_usage 请求终章 usage chunk，用于 token 计量。</li>
 * </ul>
 * 本类不依赖 Spring，由 bootstrap 装配为 Bean
 * </p>
 */
@Slf4j
public class OpenAiCompatibleLLMService implements LLMService {

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

    public OpenAiCompatibleLLMService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    @Override
    public ModelResponse stream(ModelRequest request, ModelEventListener listener) {
        long startedAt = System.currentTimeMillis();
        log.info("[llm] 调用模型，provider={}, model={}, messages={}, tools={}",
                request.provider().type(), request.model(), request.messages().size(), request.tools().size());
        try {
            HttpRequest httpRequest = buildHttpRequest(request);
            HttpResponse<InputStream> response =
                    httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                // 错误分支也必须关闭响应流，否则连接不归还（持续失败会耗尽连接）
                String error;
                try (InputStream body = response.body()) {
                    error = new String(body.readAllBytes(), StandardCharsets.UTF_8);
                }
                log.error("[llm] 模型请求失败，HTTP {}，耗时 {}ms：{}",
                        response.statusCode(), System.currentTimeMillis() - startedAt, error);
                throw new RemoteException(
                        "model request failed with HTTP " + response.statusCode() + ": " + error,
                        BaseErrorCode.MODEL_INVOKE_ERROR);
            }
            ModelResponse result = readSseStream(response.body(), listener);
            log.info("[llm] 模型调用完成，model={}, 结果类型={}, 耗时 {}ms",
                    request.model(), result.getClass().getSimpleName(), System.currentTimeMillis() - startedAt);
            return result;
        } catch (IOException error) {
            throw new RemoteException("model request failed: " + error.getMessage(),
                    error, BaseErrorCode.MODEL_INVOKE_ERROR);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new RemoteException("model request interrupted", error, BaseErrorCode.MODEL_INVOKE_ERROR);
        }
    }

    /**
     * 构建 OpenAI 兼容的 chat/completions 流式请求
     */
    private HttpRequest buildHttpRequest(ModelRequest request) throws IOException {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", request.model());
        body.set("messages", buildWireMessages(request.messages()));
        body.put("stream", true);
        // include_usage 请求供应商在 [DONE] 前追加一个携带 token 用量的
        // 终章 chunk（fastclaw streamOptions 语义）；不支持的供应商会静默忽略
        body.putObject("stream_options").put("include_usage", true);
        if (request.temperature() != null) {
            body.put("temperature", request.temperature());
        }
        if (request.maxTokens() != null) {
            body.put("max_tokens", request.maxTokens());
        }
        if (!request.tools().isEmpty()) {
            body.set("tools", buildWireTools(request.tools()));
        }
        return HttpRequest.newBuilder(chatCompletionsUri(request.provider().apiBase()))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + request.provider().apiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
    }

    /**
     * 构造 wire 格式消息数组
     *
     * <p>
     * assistant 消息优先原样回传 rawAssistantJson；孤立 tool_calls
     * （其 ID 未被紧随的 tool 消息完整应答）连同悬空的 tool 回复一起剥离，
     * 否则供应商会以 "assistant message with 'tool_calls' must be
     * followed by tool messages" 拒绝整个请求（openai.go toAPIMessages）
     * </p>
     */
    private ArrayNode buildWireMessages(List<ModelMessage> messages) throws IOException {
        // rawAssistantJson 每条只解析一次：孤立检查与 wire 重放共用同一份
        // JsonNode（M3 的多轮循环会在每次模型调用重放整段历史，重复解析
        // 的成本随轮数线性放大）
        JsonNode[] rawParsed = parseRawAssistants(messages);
        boolean[] orphanAssistant = new boolean[messages.size()];
        boolean[] orphanTool = new boolean[messages.size()];
        markOrphanToolCalls(messages, rawParsed, orphanAssistant, orphanTool);

        ArrayNode wire = objectMapper.createArrayNode();
        for (int i = 0; i < messages.size(); i++) {
            if (orphanTool[i]) {
                continue;
            }
            ModelMessage message = messages.get(i);
            if (rawParsed[i] != null && !orphanAssistant[i]) {
                wire.add(rawParsed[i]);
                continue;
            }
            ObjectNode node = wire.addObject();
            node.put("role", message.role().wireValue());
            node.put("content", message.content());
            if (!message.toolCalls().isEmpty() && !orphanAssistant[i]) {
                node.set("tool_calls", buildWireToolCalls(message.toolCalls()));
            }
            if (!message.toolCallId().isEmpty()) {
                node.put("tool_call_id", message.toolCallId());
            }
        }
        return wire;
    }

    /**
     * 预解析各 assistant 消息的 rawAssistantJson（解析失败记 WARN 并按
     * 无 raw 处理，回退到结构化字段重建）
     */
    private JsonNode[] parseRawAssistants(List<ModelMessage> messages) {
        JsonNode[] parsed = new JsonNode[messages.size()];
        for (int i = 0; i < messages.size(); i++) {
            ModelMessage message = messages.get(i);
            if (message.role() != ModelMessage.Role.ASSISTANT || message.rawAssistantJson().isEmpty()) {
                continue;
            }
            try {
                parsed[i] = objectMapper.readTree(message.rawAssistantJson());
            } catch (IOException error) {
                log.warn("[llm] rawAssistantJson 解析失败，回退结构化字段重建", error);
            }
        }
        return parsed;
    }

    /**
     * 标记孤立 tool_calls：assistant 声明的 tool call ID 未被紧随其后的
     * tool 消息完整应答时，该 assistant 的 tool_calls 与引用它的 tool
     * 消息都需剥离（openai.go findOrphanToolCalls）
     */
    private void markOrphanToolCalls(
            List<ModelMessage> messages, JsonNode[] rawParsed, boolean[] orphanAssistant, boolean[] orphanTool) {
        for (int i = 0; i < messages.size(); i++) {
            ModelMessage message = messages.get(i);
            if (message.role() != ModelMessage.Role.ASSISTANT) {
                continue;
            }
            List<String> want = assistantToolCallIds(message, rawParsed[i]);
            if (want.isEmpty()) {
                continue;
            }
            int j = i + 1;
            Set<String> got = new HashSet<>();
            while (j < messages.size() && messages.get(j).role() == ModelMessage.Role.TOOL) {
                if (!messages.get(j).toolCallId().isEmpty()) {
                    got.add(messages.get(j).toolCallId());
                }
                j++;
            }
            if (got.containsAll(want)) {
                continue;
            }
            orphanAssistant[i] = true;
            for (int k = i + 1; k < j; k++) {
                if (want.contains(messages.get(k).toolCallId())) {
                    orphanTool[k] = true;
                }
            }
        }
    }

    /**
     * 提取 assistant 消息声明的 tool call ID：优先取结构化字段，
     * 老会话可能只在 rawAssistantJson 中携带（openai.go assistantToolCallIDs）
     */
    private List<String> assistantToolCallIds(ModelMessage message, JsonNode rawAssistant) {
        if (!message.toolCalls().isEmpty()) {
            return message.toolCalls().stream().map(ToolCall::id).toList();
        }
        if (rawAssistant == null) {
            return List.of();
        }
        JsonNode toolCalls = rawAssistant.path("tool_calls");
        if (!toolCalls.isArray() || toolCalls.isEmpty()) {
            return List.of();
        }
        return StreamSupport.stream(toolCalls.spliterator(), false)
                .map(node -> node.path("id").asText(""))
                .toList();
    }

    private ArrayNode buildWireToolCalls(List<ToolCall> calls) {
        ArrayNode wire = objectMapper.createArrayNode();
        for (ToolCall call : calls) {
            ObjectNode node = wire.addObject();
            node.put("id", call.id());
            node.put("type", "function");
            ObjectNode function = node.putObject("function");
            function.put("name", call.name());
            function.put("arguments", call.arguments());
        }
        return wire;
    }

    private ArrayNode buildWireTools(List<ToolDefinition> tools) {
        ArrayNode wire = objectMapper.createArrayNode();
        for (ToolDefinition tool : tools) {
            ObjectNode node = wire.addObject();
            node.put("type", "function");
            ObjectNode function = node.putObject("function");
            function.put("name", tool.name());
            function.put("description", tool.description());
            function.set("parameters", objectMapper.valueToTree(tool.parameters()));
        }
        return wire;
    }

    /**
     * 逐行解析 SSE 响应流：正文/思考增量实时回调，tool call 分片聚合，
     * usage 终章捕获，聚合出文本完成或工具请求两类最终结果
     */
    private ModelResponse readSseStream(InputStream body, ModelEventListener listener) throws IOException {
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        ToolCallAccumulator toolCalls = new ToolCallAccumulator();
        TokenUsage usage = TokenUsage.ZERO;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(data)) {
                    break;
                }
                JsonNode chunk;
                try {
                    chunk = objectMapper.readTree(data);
                } catch (IOException error) {
                    // 对齐 fastclaw：坏 chunk 记 WARN 跳过，不中断整个流
                    log.warn("[llm] SSE chunk 解析失败，跳过：{}", data, error);
                    continue;
                }
                // usage 随 include_usage 的终章 chunk（choices 为空）到达
                JsonNode usageNode = chunk.path("usage");
                if (usageNode.isObject()) {
                    usage = parseUsage(usageNode);
                }
                JsonNode choices = chunk.path("choices");
                if (!choices.isArray() || choices.isEmpty()) {
                    continue;
                }
                JsonNode delta = choices.get(0).path("delta");
                String contentDelta = delta.path("content").asText("");
                if (!contentDelta.isEmpty()) {
                    content.append(contentDelta);
                    listener.onEvent(new ModelEvent.TextDelta(contentDelta));
                }
                String reasoningDelta = delta.path("reasoning_content").asText("");
                if (!reasoningDelta.isEmpty()) {
                    reasoning.append(reasoningDelta);
                    listener.onEvent(new ModelEvent.ReasoningDelta(reasoningDelta));
                }
                for (JsonNode toolCallDelta : delta.path("tool_calls")) {
                    toolCalls.accept(
                            toolCallDelta.path("index").asInt(0),
                            toolCallDelta.path("id").asText(""),
                            toolCallDelta.path("function").path("name").asText(""),
                            toolCallDelta.path("function").path("arguments").asText(""));
                }
            }
        }

        List<ToolCall> calls = toolCalls.complete();
        String rawAssistantJson = buildRawAssistantJson(content.toString(), reasoning.toString(), calls);
        if (!calls.isEmpty()) {
            return new ModelResponse.ToolCalls(calls, content.toString(), usage, rawAssistantJson);
        }
        if (content.isEmpty()) {
            throw new RemoteException("model returned an empty response", BaseErrorCode.MODEL_INVOKE_ERROR);
        }
        return new ModelResponse.Text(content.toString(), usage, rawAssistantJson);
    }

    /**
     * 序列化供应商 wire 格式的完整 assistant 消息（fastclaw RawAssistant
     * 语义）：tool_calls 必须包含（否则下一轮请求中 tool 回复变孤立被
     * 供应商 400 拒绝），reasoning_content 必须回传（DeepSeek/Kimi
     * thinking 模式要求原样回显）
     */
    private String buildRawAssistantJson(String content, String reasoning, List<ToolCall> calls) throws IOException {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("role", "assistant");
        node.put("content", content);
        if (!reasoning.isEmpty()) {
            node.put("reasoning_content", reasoning);
        }
        if (!calls.isEmpty()) {
            node.set("tool_calls", buildWireToolCalls(calls));
        }
        return objectMapper.writeValueAsString(node);
    }

    private TokenUsage parseUsage(JsonNode usageNode) {
        long promptTokens = usageNode.path("prompt_tokens").asLong(0);
        long completionTokens = usageNode.path("completion_tokens").asLong(0);
        long cachedTokens = usageNode.path("prompt_tokens_details").path("cached_tokens").asLong(0);
        // 对齐 fastclaw openaiUsageToProvider：inputTokens 为未命中缓存的
        // 剩余部分，input + cacheRead 之和仍等于完整 prompt 大小
        return new TokenUsage(Math.max(0, promptTokens - cachedTokens), completionTokens, cachedTokens, 0);
    }

    private URI chatCompletionsUri(String apiBase) {
        String normalized = apiBase == null ? "" : apiBase.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (!normalized.endsWith("/chat/completions")) {
            normalized += "/chat/completions";
        }
        // 显式校验，避免空/相对 apiBase 在 HttpRequest.newBuilder 处抛出
        // 未分类的 IllegalArgumentException 绕过统一错误映射
        URI uri;
        try {
            uri = URI.create(normalized);
        } catch (IllegalArgumentException error) {
            throw new RemoteException("invalid model apiBase: " + apiBase,
                    error, BaseErrorCode.MODEL_INVOKE_ERROR);
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new RemoteException(
                    "model apiBase must be an absolute http(s) URL, got: "
                            + (apiBase == null || apiBase.isBlank() ? "<empty>" : apiBase),
                    BaseErrorCode.MODEL_INVOKE_ERROR);
        }
        return uri;
    }
}
