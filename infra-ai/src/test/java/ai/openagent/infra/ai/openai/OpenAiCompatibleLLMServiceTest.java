package ai.openagent.infra.ai.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.openagent.framework.exception.RemoteException;
import ai.openagent.infra.ai.LLMService;
import ai.openagent.infra.ai.model.ModelEvent;
import ai.openagent.infra.ai.model.ModelMessage;
import ai.openagent.infra.ai.model.ModelProviderConfig;
import ai.openagent.infra.ai.model.ModelRequest;
import ai.openagent.infra.ai.model.ModelResponse;
import ai.openagent.infra.ai.model.ToolCall;
import ai.openagent.infra.ai.model.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * OpenAI 兼容流式协议 fixture 测试（V2 方案 M2）
 *
 * <p>
 * 参考 fastclaw openai_stream_raw_assistant_test.go 的 SSE 脚本方式：
 * 本地 HttpServer 按固定分片回放流式响应，覆盖 arguments 跨 chunk
 * 分片、多个 tool calls、非法 JSON chunk、正文与 tool calls 混合、
 * 孤立 tool call 剥离与 rawAssistant 原样重放
 * </p>
 */
class OpenAiCompatibleLLMServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;
    private LLMService service;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private volatile List<String> sseScript = List.of();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                for (String chunk : sseScript) {
                    out.write(("data: " + chunk + "\n\n").getBytes(StandardCharsets.UTF_8));
                }
                out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
        service = new OpenAiCompatibleLLMService(objectMapper);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private ModelRequest request(List<ModelMessage> messages, List<ToolDefinition> tools) {
        String apiBase = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        return new ModelRequest(
                new ModelProviderConfig("openai-compatible", apiBase, "test-key"),
                "test-model",
                messages,
                tools,
                0.7,
                1024);
    }

    private static List<ToolDefinition> readFileTool() {
        return List.of(new ToolDefinition(
                "read_file",
                "读取 workspace 内文件",
                Map.of("type", "object", "properties", Map.of("path", Map.of("type", "string")))));
    }

    @Test
    void aggregatesToolCallArgumentsAcrossChunks() throws Exception {
        // fastclaw 同款脚本：一个 tool call，arguments 跨 chunk 分片
        sseScript = List.of(
                "{\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}",
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_abc\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"read_file\",\"arguments\":\"\"}}]}}]}",
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"function\":{\"arguments\":\"{\\\"path\\\":\"}}]}}]}",
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"function\":{\"arguments\":\"\\\"README.md\\\"}\"}}]}}]}");

        ModelResponse response =
                service.stream(request(List.of(ModelMessage.user("读取 README")), readFileTool()), event -> {});

        ModelResponse.ToolCalls toolCalls = assertInstanceOf(ModelResponse.ToolCalls.class, response);
        assertEquals(1, toolCalls.calls().size());
        ToolCall call = toolCalls.calls().get(0);
        assertEquals("call_abc", call.id());
        assertEquals("read_file", call.name());
        assertEquals("{\"path\":\"README.md\"}", call.arguments());

        // rawAssistantJson 必须携带 tool_calls（fastclaw RawAssistant 回归：
        // 缺失时下一轮请求的 tool 回复变孤立，供应商 400）
        JsonNode raw = objectMapper.readTree(toolCalls.rawAssistantJson());
        assertEquals("assistant", raw.path("role").asText());
        assertEquals("call_abc", raw.path("tool_calls").get(0).path("id").asText());
    }

    @Test
    void aggregatesMultipleToolCallsInStableOrder() {
        sseScript = List.of(
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_a\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"get_current_time\",\"arguments\":\"{\\\"timezone\\\":\\\"Asia/Shanghai\\\"}\"}}]}}]}",
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":1,\"id\":\"call_b\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"calculator\",\"arguments\":\"{\\\"expression\\\":\"}}]}}]}",
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":1,"
                        + "\"function\":{\"arguments\":\"\\\"17*8\\\"}\"}}]}}]}");

        ModelResponse response =
                service.stream(request(List.of(ModelMessage.user("多工具")), readFileTool()), event -> {});

        ModelResponse.ToolCalls toolCalls = assertInstanceOf(ModelResponse.ToolCalls.class, response);
        assertEquals(2, toolCalls.calls().size());
        assertEquals("call_a", toolCalls.calls().get(0).id());
        assertEquals("call_b", toolCalls.calls().get(1).id());
        assertEquals("{\"expression\":\"17*8\"}", toolCalls.calls().get(1).arguments());
    }

    @Test
    void skipsMalformedChunkWithoutBreakingStream() {
        sseScript = List.of(
                "{\"choices\":[{\"delta\":{\"content\":\"Hello \"}}]}",
                "{not valid json at all",
                "{\"choices\":[{\"delta\":{\"content\":\"world\"}}]}");

        ModelResponse response = service.stream(request(List.of(ModelMessage.user("hi")), List.of()), event -> {});

        ModelResponse.Text text = assertInstanceOf(ModelResponse.Text.class, response);
        assertEquals("Hello world", text.content());
    }

    @Test
    void plainTextStreamsDeltasAndReportsUsage() {
        sseScript = List.of(
                "{\"choices\":[{\"delta\":{\"content\":\"Hello \"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"from OpenAgent\"}}]}",
                "{\"choices\":[],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20,"
                        + "\"prompt_tokens_details\":{\"cached_tokens\":60}}}");

        List<String> deltas = new ArrayList<>();
        ModelResponse response = service.stream(request(List.of(ModelMessage.user("hi")), List.of()), event -> {
            if (event instanceof ModelEvent.TextDelta delta) {
                deltas.add(delta.text());
            }
        });

        ModelResponse.Text text = assertInstanceOf(ModelResponse.Text.class, response);
        assertEquals("Hello from OpenAgent", text.content());
        assertEquals(List.of("Hello ", "from OpenAgent"), deltas);
        // usage：input 为未命中缓存的剩余（100-60），cacheRead 单列
        assertEquals(40, text.usage().inputTokens());
        assertEquals(20, text.usage().outputTokens());
        assertEquals(60, text.usage().cacheReadTokens());
    }

    @Test
    void keepsContentAlongsideToolCalls() {
        // 模型同时返回正文与 tool calls：正文必须保留（V2 方案 1.2 行为 5）
        sseScript = List.of(
                "{\"choices\":[{\"delta\":{\"content\":\"我先看一下文件。\"}}]}",
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_x\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"read_file\",\"arguments\":\"{}\"}}]}}]}");

        ModelResponse response =
                service.stream(request(List.of(ModelMessage.user("看文件")), readFileTool()), event -> {});

        ModelResponse.ToolCalls toolCalls = assertInstanceOf(ModelResponse.ToolCalls.class, response);
        assertEquals("我先看一下文件。", toolCalls.content());
        assertEquals("call_x", toolCalls.calls().get(0).id());
    }

    @Test
    void requestCarriesToolsFieldOnlyWhenToolsPresent() throws Exception {
        sseScript = List.of("{\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}");

        service.stream(request(List.of(ModelMessage.user("hi")), readFileTool()), event -> {});
        JsonNode withTools = objectMapper.readTree(lastRequestBody.get());
        assertEquals("function", withTools.path("tools").get(0).path("type").asText());
        assertEquals("read_file", withTools.path("tools").get(0).path("function").path("name").asText());
        assertTrue(withTools.path("stream").asBoolean());
        assertTrue(withTools.path("stream_options").path("include_usage").asBoolean());

        service.stream(request(List.of(ModelMessage.user("hi")), List.of()), event -> {});
        JsonNode withoutTools = objectMapper.readTree(lastRequestBody.get());
        assertTrue(withoutTools.path("tools").isMissingNode(),
                "tools 为空时请求体不得携带 tools 字段（fastclaw 语义）");
    }

    @Test
    void toolResultMessageCarriesToolCallIdOnWire() throws Exception {
        sseScript = List.of("{\"choices\":[{\"delta\":{\"content\":\"done\"}}]}");
        List<ModelMessage> messages = List.of(
                ModelMessage.user("读取 README"),
                new ModelMessage(
                        ModelMessage.Role.ASSISTANT,
                        "",
                        List.of(new ToolCall("call_abc", "read_file", "{\"path\":\"README.md\"}")),
                        "",
                        ""),
                ModelMessage.tool("call_abc", "# OpenAgent\n..."));

        service.stream(request(messages, readFileTool()), event -> {});

        JsonNode wire = objectMapper.readTree(lastRequestBody.get()).path("messages");
        assertEquals(3, wire.size());
        assertEquals("call_abc", wire.get(1).path("tool_calls").get(0).path("id").asText());
        assertEquals("tool", wire.get(2).path("role").asText());
        assertEquals("call_abc", wire.get(2).path("tool_call_id").asText());
    }

    @Test
    void prefersRawAssistantJsonOnReplay() throws Exception {
        sseScript = List.of("{\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}");
        String raw = "{\"role\":\"assistant\",\"content\":\"hi\",\"reasoning_content\":\"thinking...\"}";
        List<ModelMessage> messages = List.of(
                ModelMessage.user("hello"),
                new ModelMessage(ModelMessage.Role.ASSISTANT, "hi", List.of(), "", raw),
                ModelMessage.user("again"));

        service.stream(request(messages, List.of()), event -> {});

        JsonNode wire = objectMapper.readTree(lastRequestBody.get()).path("messages");
        // rawAssistantJson 原样回传：reasoning_content 等扩展字段不丢失
        assertEquals("thinking...", wire.get(1).path("reasoning_content").asText());
    }

    @Test
    void stripsOrphanToolCallsFromWireMessages() throws Exception {
        sseScript = List.of("{\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}");
        // assistant 声明了 call_1/call_2，但只有 call_1 有 tool 回复
        // → 该 assistant 的 tool_calls 与悬空的 tool 回复都必须剥离
        List<ModelMessage> messages = List.of(
                ModelMessage.user("do things"),
                new ModelMessage(
                        ModelMessage.Role.ASSISTANT,
                        "",
                        List.of(
                                new ToolCall("call_1", "read_file", "{}"),
                                new ToolCall("call_2", "list_dir", "{}")),
                        "",
                        ""),
                ModelMessage.tool("call_1", "partial result"),
                ModelMessage.user("continue"));

        service.stream(request(messages, List.of()), event -> {});

        JsonNode wire = objectMapper.readTree(lastRequestBody.get()).path("messages");
        assertEquals(3, wire.size(), "悬空 tool 回复应被剥离");
        assertTrue(wire.get(1).path("tool_calls").isMissingNode(), "孤立 tool_calls 应被剥离");
        for (JsonNode message : wire) {
            assertFalse("tool".equals(message.path("role").asText()), "不得残留悬空 tool 消息");
        }
    }

    @Test
    void completeToolCallPairsSurviveOrphanCheck() throws Exception {
        sseScript = List.of("{\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}");
        // 完整配对的 assistant tool_calls + tool 回复不得被误剥离
        List<ModelMessage> messages = List.of(
                ModelMessage.user("do things"),
                new ModelMessage(
                        ModelMessage.Role.ASSISTANT,
                        "",
                        List.of(
                                new ToolCall("call_1", "read_file", "{}"),
                                new ToolCall("call_2", "list_dir", "{}")),
                        "",
                        ""),
                ModelMessage.tool("call_1", "result 1"),
                ModelMessage.tool("call_2", "result 2"));

        service.stream(request(messages, List.of()), event -> {});

        JsonNode wire = objectMapper.readTree(lastRequestBody.get()).path("messages");
        assertEquals(4, wire.size());
        assertEquals(2, wire.get(1).path("tool_calls").size());
        assertEquals("tool", wire.get(2).path("role").asText());
        assertEquals("tool", wire.get(3).path("role").asText());
    }

    @Test
    void emptyResponseThrowsRemoteException() {
        sseScript = List.of();
        assertThrows(RemoteException.class,
                () -> service.stream(request(List.of(ModelMessage.user("hi")), List.of()), event -> {}));
    }

    @Test
    void blankApiBaseThrowsRemoteExceptionNotIllegalArgument() {
        // 空/相对 apiBase 必须走统一的 RemoteException 错误映射，
        // 不得以未分类的 IllegalArgumentException 逃逸
        ModelRequest blankBase = new ModelRequest(
                new ModelProviderConfig("openai-compatible", "", "test-key"),
                "test-model",
                List.of(ModelMessage.user("hi")),
                List.of(),
                0.7,
                1024);
        assertThrows(RemoteException.class, () -> service.stream(blankBase, event -> {}));
    }
}
