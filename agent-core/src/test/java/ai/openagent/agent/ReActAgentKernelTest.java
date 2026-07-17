package ai.openagent.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.openagent.agent.tool.AgentTool;
import ai.openagent.agent.tool.ToolDescriptor;
import ai.openagent.agent.tool.ToolErrorCode;
import ai.openagent.agent.tool.ToolInvoker;
import ai.openagent.agent.tool.ToolRegistry;
import ai.openagent.agent.tool.ToolResult;
import ai.openagent.agent.tool.ToolUnavailableException;
import ai.openagent.infra.ai.LLMService;
import ai.openagent.infra.ai.model.ModelMessage;
import ai.openagent.infra.ai.model.ModelRequest;
import ai.openagent.infra.ai.model.ModelResponse;
import ai.openagent.infra.ai.model.TokenUsage;
import ai.openagent.infra.ai.model.ToolCall;
import ai.openagent.infra.ai.model.ToolDefinition;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * ReAct 内核 Fake Model 闭环测试（V2 方案 M3 退出条件：Fake Model 驱动
 * 「工具请求 → 工具结果 → 最终文本」完整闭环，及 16.1 各循环边界）
 */
class ReActAgentKernelTest {

    private static final AgentRuntimeConfig CONFIG =
            new AgentRuntimeConfig(3, Duration.ofMinutes(1), Duration.ofSeconds(5));

    // ==================== 测试基建 ====================

    /**
     * 脚本化模型：按调用次序弹出预设响应；记录每次请求供断言
     */
    private static final class FakeModel implements LLMService {
        private final Deque<ModelResponse> script = new ArrayDeque<>();
        final List<ModelRequest> requests = new ArrayList<>();

        FakeModel enqueue(ModelResponse response) {
            script.add(response);
            return this;
        }

        @Override
        public ModelResponse stream(ModelRequest request, ai.openagent.infra.ai.ModelEventListener listener) {
            requests.add(request);
            if (script.isEmpty()) {
                throw new IllegalStateException("fake model script exhausted");
            }
            return script.pop();
        }
    }

    /**
     * 单工具注册表 + 可编程执行结果的 Invoker
     */
    private static final class FakeTools implements ToolRegistry, ToolInvoker {
        private final Function<ToolCall, ToolResult> executor;
        final List<ToolCall> invoked = new ArrayList<>();

        FakeTools(Function<ToolCall, ToolResult> executor) {
            this.executor = executor;
        }

        @Override
        public List<ToolDescriptor> availableTools(String agentId) {
            return List.of(new ToolDescriptor(
                    "echo", "echo tool", Map.of("type", "object"), ToolDescriptor.Source.BUILTIN));
        }

        @Override
        public AgentTool requireEnabled(String agentId, String toolName) {
            throw new ToolUnavailableException(ToolErrorCode.TOOL_NOT_FOUND, "not used in kernel tests");
        }

        @Override
        public ToolResult invoke(ToolCall call, ai.openagent.agent.tool.ToolExecutionContext context) {
            invoked.add(call);
            return executor.apply(call);
        }
    }

    /**
     * 内存会话：记录追加的消息与请求构建，workspace 指向临时目录
     */
    private static final class FakeConversation implements AgentConversation, AgentConversationFactory {
        final List<ModelMessage> messages = new ArrayList<>();
        final List<String> appendedRoles = new ArrayList<>();
        List<ModelMessage> lastTransientNotes = List.of();
        List<ToolDefinition> lastTools = List.of();

        FakeConversation() {
            messages.add(ModelMessage.system("test system prompt"));
            messages.add(ModelMessage.user("test task"));
        }

        @Override
        public AgentConversation open(AgentRunCommand command) {
            return this;
        }

        @Override
        public ModelRequest buildRequest(List<ToolDefinition> tools, List<ModelMessage> transientNotes) {
            lastTools = tools;
            lastTransientNotes = transientNotes;
            List<ModelMessage> requestMessages = new ArrayList<>(messages);
            requestMessages.addAll(transientNotes);
            return new ModelRequest(
                    new ai.openagent.infra.ai.model.ModelProviderConfig("openai", "http://test", "key"),
                    "fake-model",
                    requestMessages,
                    tools,
                    null,
                    null);
        }

        @Override
        public void appendAssistant(
                String content, List<ToolCall> toolCalls, String rawAssistantJson, Map<String, Object> metadata) {
            appendedRoles.add("assistant");
            messages.add(new ModelMessage(ModelMessage.Role.ASSISTANT, content, toolCalls, "", rawAssistantJson));
        }

        @Override
        public void appendToolResult(ToolCall call, ToolResult result) {
            appendedRoles.add("tool");
            messages.add(ModelMessage.tool(call.id(), result.observation()));
        }

        @Override
        public Path workspace() {
            return Path.of("target", "test-workspace");
        }
    }

    private static AgentRunCommand command() {
        return new AgentRunCommand("run-1", "local-user", "default", "session-1", "test task", CONFIG);
    }

    private static ModelResponse.ToolCalls toolCallResponse(String id, String arguments) {
        return new ModelResponse.ToolCalls(
                List.of(new ToolCall(id, "echo", arguments)), "", TokenUsage.ZERO, "");
    }

    private static ModelResponse.Text textResponse(String content) {
        return new ModelResponse.Text(content, TokenUsage.ZERO, "");
    }

    private record Harness(
            ReActAgentKernel kernel, FakeModel model, FakeTools tools, FakeConversation conversation,
            List<AgentEvent> events, AgentEventSink sink) {

        static Harness of(FakeModel model, FakeTools tools) {
            FakeConversation conversation = new FakeConversation();
            ReActAgentKernel kernel = new ReActAgentKernel(model, tools, tools, conversation);
            List<AgentEvent> events = new ArrayList<>();
            return new Harness(kernel, model, tools, conversation, events, events::add);
        }

        AgentRunResult run() {
            return kernel.run(command(), sink);
        }

        long count(Class<? extends AgentEvent> type) {
            return events.stream().filter(type::isInstance).count();
        }
    }

    // ==================== 核心闭环 ====================

    @Test
    void completesToolRoundTripToFinalText() {
        FakeModel model = new FakeModel()
                .enqueue(toolCallResponse("call_1", "{\"text\":\"hi\"}"))
                .enqueue(textResponse("final answer"));
        FakeTools tools = new FakeTools(call -> ToolResult.success("echo: hi"));
        Harness harness = Harness.of(model, tools);

        AgentRunResult result = harness.run();

        assertEquals(AgentRunStatus.COMPLETED, result.status());
        assertEquals("final answer", result.finalContent());
        assertEquals(1, result.toolIterations());
        assertEquals(1, harness.tools().invoked.size());
        // 事件顺序：tool_call → tool_result → content → done
        assertEquals(1, harness.count(AgentEvent.ToolCallRequested.class));
        assertEquals(1, harness.count(AgentEvent.ToolResultProduced.class));
        assertEquals(1, harness.count(AgentEvent.Content.class));
        assertEquals(1, harness.count(AgentEvent.Done.class));
        // 消息配对：assistant(tool_calls) → tool → assistant(final)
        assertEquals(List.of("assistant", "tool", "assistant"), harness.conversation().appendedRoles);
        // 第二次模型调用能看到 tool result
        ModelRequest second = harness.model().requests.get(1);
        assertTrue(second.messages().stream()
                .anyMatch(m -> m.role() == ModelMessage.Role.TOOL && m.toolCallId().equals("call_1")));
    }

    @Test
    void plainTextCompletesWithoutTools() {
        FakeModel model = new FakeModel().enqueue(textResponse("just a chat"));
        Harness harness = Harness.of(model, new FakeTools(call -> ToolResult.success("unused")));

        AgentRunResult result = harness.run();

        assertEquals(AgentRunStatus.COMPLETED, result.status());
        assertEquals(0, result.toolIterations());
        assertEquals(0, harness.tools().invoked.size());
    }

    @Test
    void contentAlongsideToolCallsIsEmittedBeforeToolEvents() {
        FakeModel model = new FakeModel()
                .enqueue(new ModelResponse.ToolCalls(
                        List.of(new ToolCall("call_1", "echo", "{}")),
                        "let me check", TokenUsage.ZERO, ""))
                .enqueue(textResponse("done"));
        Harness harness = Harness.of(model, new FakeTools(call -> ToolResult.success("ok")));

        harness.run();

        // 正文事件先于 tool_call 事件（V2 方案 1.2 行为 5）
        int contentIndex = -1;
        int toolCallIndex = -1;
        for (int i = 0; i < harness.events().size(); i++) {
            if (harness.events().get(i) instanceof AgentEvent.Content && contentIndex < 0) {
                contentIndex = i;
            }
            if (harness.events().get(i) instanceof AgentEvent.ToolCallRequested && toolCallIndex < 0) {
                toolCallIndex = i;
            }
        }
        assertTrue(contentIndex >= 0 && toolCallIndex >= 0 && contentIndex < toolCallIndex,
                "正文事件应先于 tool_call 事件");
    }

    // ==================== 循环边界 ====================

    @Test
    void loopProtectionTripsOnThreeIdenticalCalls() {
        // 每轮返回完全相同的 (工具, arguments)，第 3 轮触发循环保护
        FakeModel model = new FakeModel()
                .enqueue(toolCallResponse("call_1", "{\"a\":1}"))
                .enqueue(toolCallResponse("call_2", "{\"a\":1}"))
                .enqueue(toolCallResponse("call_3", "{\"a\":1}"))
                .enqueue(textResponse("synthesized after loop"));
        FakeTools tools = new FakeTools(call -> ToolResult.success("same"));
        Harness harness = Harness.of(model, tools);

        AgentRunResult result = harness.run();

        assertEquals(AgentRunStatus.LIMIT_REACHED, result.status());
        assertEquals("synthesized after loop", result.finalContent());
        // 第 3 轮触发保护：该轮工具不执行（fastclaw 执行前检查）
        assertEquals(2, harness.tools().invoked.size());
        // 最终交付调用不携带 tools 且注入循环提示
        ModelRequest last = harness.model().requests.get(harness.model().requests.size() - 1);
        assertTrue(last.tools().isEmpty(), "最终交付不得携带 tools");
        assertTrue(harness.conversation().lastTransientNotes.stream()
                .anyMatch(n -> n.content().contains("Loop detected")));
    }

    @Test
    void differentArgumentsDoNotTripLoopProtection() {
        FakeModel model = new FakeModel()
                .enqueue(toolCallResponse("call_1", "{\"a\":1}"))
                .enqueue(toolCallResponse("call_2", "{\"a\":2}"))
                .enqueue(toolCallResponse("call_3", "{\"a\":3}"))
                .enqueue(textResponse("cap reached synthesis"));
        FakeTools tools = new FakeTools(call -> ToolResult.success("ok"));
        Harness harness = Harness.of(model, tools);

        AgentRunResult result = harness.run();

        // 3 轮迭代耗尽（maxToolIterations=3），全部执行，走迭代上限交付
        assertEquals(AgentRunStatus.LIMIT_REACHED, result.status());
        assertEquals(3, harness.tools().invoked.size());
        assertEquals(3, result.toolIterations());
    }

    @Test
    void threeAllFailedRoundsDisableToolsWithSystemNote() {
        FakeModel model = new FakeModel()
                .enqueue(toolCallResponse("call_1", "{\"u\":1}"))
                .enqueue(toolCallResponse("call_2", "{\"u\":2}"))
                .enqueue(toolCallResponse("call_3", "{\"u\":3}"))
                .enqueue(textResponse("best effort answer"));
        FakeTools tools = new FakeTools(call ->
                ToolResult.failure(ToolErrorCode.TOOL_EXECUTION_FAILED, "http 404"));
        // maxToolIterations=4 保证第 4 轮仍在循环内（而非迭代上限路径）
        AgentRuntimeConfig config = new AgentRuntimeConfig(4, Duration.ofMinutes(1), Duration.ofSeconds(5));
        FakeConversation conversation = new FakeConversation();
        ReActAgentKernel kernel = new ReActAgentKernel(model, tools, tools, conversation);
        List<AgentEvent> events = new ArrayList<>();

        AgentRunResult result = kernel.run(
                new AgentRunCommand("run-1", "local-user", "default", "session-1", "task", config),
                events::add);

        // 第 4 轮：连续 3 轮全失败 → 不携带 tools + 注入 system 提示 → 文本完成
        assertEquals(AgentRunStatus.COMPLETED, result.status());
        assertEquals("best effort answer", result.finalContent());
        ModelRequest fourth = model.requests.get(3);
        assertTrue(fourth.tools().isEmpty(), "连续全失败后应禁用 tools");
        assertTrue(conversation.lastTransientNotes.stream()
                .anyMatch(n -> n.content().contains("rounds of tool calls all failed")));
    }

    @Test
    void iterationCapForcesFinalDeliveryWithMetadata() {
        FakeModel model = new FakeModel()
                .enqueue(toolCallResponse("call_1", "{\"n\":1}"))
                .enqueue(toolCallResponse("call_2", "{\"n\":2}"))
                .enqueue(toolCallResponse("call_3", "{\"n\":3}"))
                .enqueue(textResponse("cap synthesis"));
        Harness harness = Harness.of(model, new FakeTools(call -> ToolResult.success("ok")));

        AgentRunResult result = harness.run();

        assertEquals(AgentRunStatus.LIMIT_REACHED, result.status());
        // 迭代上限 metadata 盖在最终 content 事件上（前端 badge 依赖）
        AgentEvent.Content finalContent = (AgentEvent.Content) harness.events().stream()
                .filter(e -> e instanceof AgentEvent.Content)
                .reduce((a, b) -> b)
                .orElseThrow();
        assertEquals(true, finalContent.metadata().get("iterationCapReached"));
        assertEquals(3, finalContent.metadata().get("iterationCapValue"));
    }

    @Test
    void finalSynthesisFailureFallsBackToCannedText() {
        FakeModel model = new FakeModel()
                .enqueue(toolCallResponse("call_1", "{\"n\":1}"))
                .enqueue(toolCallResponse("call_2", "{\"n\":2}"))
                .enqueue(toolCallResponse("call_3", "{\"n\":3}"));
        // 第 4 次调用（最终总结）脚本耗尽抛异常 → 兜底文本
        Harness harness = Harness.of(model, new FakeTools(call -> ToolResult.success("ok")));

        AgentRunResult result = harness.run();

        assertEquals(AgentRunStatus.LIMIT_REACHED, result.status());
        assertTrue(result.finalContent().contains("maximum number of tool iterations"),
                "总结失败应使用固定兜底文本: " + result.finalContent());
    }

    // ==================== 配对与失败恢复 ====================

    @Test
    void invokerEscapeSynthesizesFailureResultKeepingPairing() {
        FakeModel model = new FakeModel()
                .enqueue(toolCallResponse("call_1", "{}"))
                .enqueue(textResponse("recovered"));
        FakeTools tools = new FakeTools(call -> {
            throw new IllegalStateException("invoker bug");
        });
        Harness harness = Harness.of(model, tools);

        AgentRunResult result = harness.run();

        // 异常合成失败结果，配对闭合，模型继续推理
        assertEquals(AgentRunStatus.COMPLETED, result.status());
        assertEquals(List.of("assistant", "tool", "assistant"), harness.conversation().appendedRoles);
        AgentEvent.ToolResultProduced toolResult = (AgentEvent.ToolResultProduced) harness.events().stream()
                .filter(e -> e instanceof AgentEvent.ToolResultProduced)
                .findFirst()
                .orElseThrow();
        assertTrue(toolResult.result().contains(ToolErrorCode.TOOL_EXECUTION_FAILED));
    }

    @Test
    void nullInvokerResultSynthesizesMissingResult() {
        FakeModel model = new FakeModel()
                .enqueue(toolCallResponse("call_1", "{}"))
                .enqueue(textResponse("recovered"));
        FakeTools tools = new FakeTools(call -> null);
        Harness harness = Harness.of(model, tools);

        AgentRunResult result = harness.run();

        assertEquals(AgentRunStatus.COMPLETED, result.status());
        AgentEvent.ToolResultProduced toolResult = (AgentEvent.ToolResultProduced) harness.events().stream()
                .filter(e -> e instanceof AgentEvent.ToolResultProduced)
                .findFirst()
                .orElseThrow();
        assertTrue(toolResult.result().contains(ToolErrorCode.TOOL_RESULT_MISSING));
    }

    @Test
    void multipleToolCallsExecuteInOrderWithPairedResults() {
        FakeModel model = new FakeModel()
                .enqueue(new ModelResponse.ToolCalls(
                        List.of(
                                new ToolCall("call_a", "echo", "{\"n\":1}"),
                                new ToolCall("call_b", "echo", "{\"n\":2}")),
                        "", TokenUsage.ZERO, ""))
                .enqueue(textResponse("both done"));
        FakeTools tools = new FakeTools(call -> ToolResult.success("ok:" + call.id()));
        Harness harness = Harness.of(model, tools);

        AgentRunResult result = harness.run();

        assertEquals(AgentRunStatus.COMPLETED, result.status());
        // 稳定顺序串行执行（V2 方案 6.2）
        assertEquals(List.of("call_a", "call_b"),
                harness.tools().invoked.stream().map(ToolCall::id).toList());
        assertEquals(2, harness.count(AgentEvent.ToolResultProduced.class));
    }

    // ==================== 失败与收敛 ====================

    @Test
    void modelFailureEmitsErrorAndDone() {
        FakeModel model = new FakeModel(); // 脚本为空，首次调用即抛异常
        Harness harness = Harness.of(model, new FakeTools(call -> ToolResult.success("unused")));

        AgentRunResult result = harness.run();

        assertEquals(AgentRunStatus.FAILED, result.status());
        assertEquals(1, harness.count(AgentEvent.RunFailed.class));
        assertEquals(1, harness.count(AgentEvent.Done.class));
    }

    @Test
    void doneIsAlwaysEmittedOnSuccess() {
        FakeModel model = new FakeModel().enqueue(textResponse("hi"));
        Harness harness = Harness.of(model, new FakeTools(call -> ToolResult.success("unused")));

        harness.run();

        assertInstanceOf(AgentEvent.Done.class, harness.events().get(harness.events().size() - 1));
    }
}
