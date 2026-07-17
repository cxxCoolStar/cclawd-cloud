package ai.openagent.infra.ai.openai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.openagent.infra.ai.model.ModelMessage;
import ai.openagent.infra.ai.model.ModelProviderConfig;
import ai.openagent.infra.ai.model.ModelRequest;
import ai.openagent.infra.ai.model.ModelResponse;
import ai.openagent.infra.ai.model.ToolCall;
import ai.openagent.infra.ai.model.ToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * kimi-k2.5 真实 Tool Calling smoke test（V2 方案 M2 退出条件）
 *
 * <p>
 * 仅在显式设置 OPENAGENT_SMOKE_TEST=true 且提供模型凭证时运行，
 * 常规 CI/verify 不触发。运行方式（Git Bash）：
 * <pre>
 * export $(grep -v '^#' .env | xargs) OPENAGENT_SMOKE_TEST=true
 * JAVA_HOME=D:/software/Java/java17 ./mvnw -pl infra-ai -am test -Dtest=KimiSmokeTest
 * </pre>
 * </p>
 */
@EnabledIfEnvironmentVariable(named = "OPENAGENT_SMOKE_TEST", matches = "true")
class KimiSmokeTest {

    private final OpenAiCompatibleLLMService service = new OpenAiCompatibleLLMService(new ObjectMapper());

    private ModelRequest request(List<ModelMessage> messages, List<ToolDefinition> tools) {
        return new ModelRequest(
                new ModelProviderConfig(
                        env("OPENAGENT_MODEL_PROVIDER", "openai-compatible"),
                        env("OPENAGENT_MODEL_API_BASE", ""),
                        env("OPENAGENT_MODEL_API_KEY", "")),
                env("OPENAGENT_MODEL", "kimi-k2.5"),
                messages,
                tools,
                0.7,
                1024);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static final ToolDefinition TIME_TOOL = new ToolDefinition(
            "get_current_time",
            "按指定时区返回当前时间",
            Map.of(
                    "type", "object",
                    "properties", Map.of("timezone", Map.of(
                            "type", "string",
                            "description", "IANA 时区名，如 Asia/Shanghai")),
                    "required", List.of("timezone")));

    @Test
    void plainChatCompletes() {
        StringBuilder streamed = new StringBuilder();
        ModelResponse response = service.stream(
                request(List.of(
                        ModelMessage.system("You are a helpful assistant."),
                        ModelMessage.user("用一句话介绍你自己。")), List.of()),
                event -> {
                    if (event instanceof ai.openagent.infra.ai.model.ModelEvent.TextDelta delta) {
                        streamed.append(delta.text());
                    }
                });
        ModelResponse.Text text = assertInstanceOf(ModelResponse.Text.class, response);
        assertFalse(text.content().isBlank());
        System.out.println("[smoke] 普通聊天: " + text.content());
        System.out.println("[smoke] usage: " + text.usage());
    }

    @Test
    void toolCallingRoundTripCompletes() {
        // 第一轮：模型应选择 get_current_time
        ModelResponse first = service.stream(
                request(List.of(
                        ModelMessage.system("You are a helpful assistant. Use tools when needed."),
                        ModelMessage.user("现在上海几点了？请用工具查询。")), List.of(TIME_TOOL)),
                event -> {});
        ModelResponse.ToolCalls toolCalls = assertInstanceOf(ModelResponse.ToolCalls.class, first,
                "kimi-k2.5 应对时间问题发起 tool call，实际返回: " + first);
        ToolCall call = toolCalls.calls().get(0);
        System.out.println("[smoke] tool call: id=" + call.id() + ", name=" + call.name()
                + ", arguments=" + call.arguments());
        assertFalse(call.id().isBlank());
        assertTrue("get_current_time".equals(call.name()));

        // 第二轮：回传 tool result，模型应给出含时间的最终回答
        ModelResponse second = service.stream(
                request(List.of(
                        ModelMessage.system("You are a helpful assistant. Use tools when needed."),
                        ModelMessage.user("现在上海几点了？请用工具查询。"),
                        new ModelMessage(
                                ModelMessage.Role.ASSISTANT,
                                toolCalls.content(),
                                toolCalls.calls(),
                                "",
                                toolCalls.rawAssistantJson()),
                        ModelMessage.tool(call.id(), "2026-07-17 10:30:00 (Asia/Shanghai)")), List.of(TIME_TOOL)),
                event -> {});
        ModelResponse.Text answer = assertInstanceOf(ModelResponse.Text.class, second);
        System.out.println("[smoke] 最终回答: " + answer.content());
        assertTrue(answer.content().contains("10") || answer.content().contains("十"),
                "最终回答应引用工具返回的时间: " + answer.content());
    }
}
