package ai.openagent.agent;

import ai.openagent.agent.tool.ToolErrorCode;
import ai.openagent.agent.tool.ToolExecutionContext;
import ai.openagent.agent.tool.ToolInvoker;
import ai.openagent.agent.tool.ToolRegistry;
import ai.openagent.agent.tool.ToolResult;
import ai.openagent.infra.ai.LLMService;
import ai.openagent.infra.ai.model.ModelEvent;
import ai.openagent.infra.ai.model.ModelMessage;
import ai.openagent.infra.ai.model.ModelResponse;
import ai.openagent.infra.ai.model.ToolCall;
import ai.openagent.infra.ai.model.ToolDefinition;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * ReAct 多轮循环内核（V2 方案第 6 章状态机，对照 fastclaw
 * internal/agent/loop.go 逐段移植）
 *
 * <p>
 * 保留的 fastclaw 行为（V2 方案 1.2 必测行为）：
 * <ul>
 *   <li>连续 3 次相同工具 + 相同 arguments 触发循环保护
 *       （loop.go:2194 consecutiveCount >= 3），注入提示后进入最终交付；</li>
 *   <li>连续 3 轮工具全部失败后下一次调用不携带 tools，注入 system
 *       提示要求尽力回答（loop.go:2081）；</li>
 *   <li>迭代上限后不携带 tools 做最终总结（capReachedNudge），总结
 *       失败才用固定兜底文本（loop.go:2392-2411），并盖
 *       iterationCapReached metadata（前端 badge 依赖）；</li>
 *   <li>每个 tool call 必有配对 tool result；执行器漏返回时合成失败
 *       结果（loop.go:2272 defensive backstop）；</li>
 *   <li>正文与 tool calls 并存时先发 content 事件再发 tool_call 事件，
 *       assistant 消息二者一并入历史（顺序保留）。</li>
 * </ul>
 * 有意收缩：同一响应的多个工具调用按顺序串行执行（V2 方案 6.2，
 * 避免写工具竞争同一 workspace）；无 steer / hooks / PII scrub /
 * 媒体提取（不在 V2 范围）
 * </p>
 */
@Slf4j
@RequiredArgsConstructor
public class ReActAgentKernel implements AgentKernel {

    /**
     * 相同 (工具, arguments) 连续出现次数阈值（fastclaw 同值）
     */
    private static final int LOOP_PROTECTION_THRESHOLD = 3;

    /**
     * 连续全失败轮数阈值（fastclaw failedRoundsLimit 同值）
     */
    private static final int FAILED_ROUNDS_LIMIT = 3;

    private final LLMService llmService;
    private final ToolRegistry toolRegistry;
    private final ToolInvoker toolInvoker;
    private final AgentConversationFactory conversationFactory;

    @Override
    public AgentRunResult run(AgentRunCommand command, AgentEventSink sink) {
        try {
            AgentRunResult result = runLoop(command, sink);
            sink.emit(new AgentEvent.Done());
            return result;
        } catch (RuntimeException error) {
            // 模型调用失败 / 持久化失败：运行失败，error + done 必达
            // （V2 方案 6.1 规则 5）
            log.error("[kernel] 运行失败，runId={}", command.runId(), error);
            sink.emit(new AgentEvent.RunFailed(rootMessage(error)));
            sink.emit(new AgentEvent.Done());
            return new AgentRunResult(
                    command.runId(), AgentRunStatus.FAILED, "", 0, "RUN_FAILED", rootMessage(error));
        }
    }

    private AgentRunResult runLoop(AgentRunCommand command, AgentEventSink sink) {
        AgentConversation conversation = conversationFactory.open(command);
        List<ToolDefinition> toolDefinitions = toolRegistry.availableTools(command.agentId()).stream()
                .map(d -> new ToolDefinition(d.name(), d.description(), d.inputSchema()))
                .toList();
        Instant runDeadline = Instant.now().plus(command.config().runTimeout());

        // 循环保护：跟踪连续相同 (工具名, arguments 摘要)
        String lastSignature = null;
        int consecutiveCount = 0;
        // 连续全失败轮数
        int allFailedRounds = 0;
        int iterations = 0;
        // 循环保护触发后跳出循环、走最终交付（fastclaw loopDetected break）
        boolean loopProtectionTripped = false;

        for (; iterations < command.config().maxToolIterations(); iterations++) {
            log.info("[kernel] 循环迭代，runId={}, iteration={}/{}",
                    command.runId(), iterations + 1, command.config().maxToolIterations());
            if (Instant.now().isAfter(runDeadline)) {
                return timedOut(command, sink, iterations);
            }

            // 连续全失败达到阈值：本次调用禁用工具并注入 system 提示
            List<ToolDefinition> callTools = toolDefinitions;
            List<ModelMessage> transientNotes = List.of();
            if (allFailedRounds >= FAILED_ROUNDS_LIMIT) {
                log.warn("[kernel] 连续 {} 轮工具全部失败，禁用工具尽力回答，runId={}",
                        allFailedRounds, command.runId());
                callTools = List.of();
                transientNotes = List.of(ModelMessage.system(
                        "The last " + allFailedRounds + " rounds of tool calls all failed. "
                                + "Stop calling tools and answer the user directly with what you know — "
                                + "provide your best-effort response, clearly marked as unverified."));
            }

            ModelResponse response = llmService.stream(
                    conversation.buildRequest(callTools, transientNotes),
                    event -> {
                        if (event instanceof ModelEvent.TextDelta delta) {
                            sink.emit(new AgentEvent.ContentDelta(delta.text()));
                        }
                    });

            if (response instanceof ModelResponse.Text text) {
                // 普通文本完成：正常终态
                conversation.appendAssistant(text.content(), List.of(), text.rawAssistantJson(), Map.of());
                sink.emit(new AgentEvent.Content(text.content(), Map.of()));
                return new AgentRunResult(
                        command.runId(), AgentRunStatus.COMPLETED, text.content(), iterations, "", "");
            }

            ModelResponse.ToolCalls toolCalls = (ModelResponse.ToolCalls) response;
            // 正文先于 tool calls 发布（顺序保留，V2 方案 1.2 行为 5）
            if (!toolCalls.content().isBlank()) {
                sink.emit(new AgentEvent.Content(toolCalls.content(), Map.of()));
            }
            for (ToolCall call : toolCalls.calls()) {
                sink.emit(new AgentEvent.ToolCallRequested(call.id(), call.name(), call.arguments()));
            }
            conversation.appendAssistant(
                    toolCalls.content(), toolCalls.calls(), toolCalls.rawAssistantJson(), Map.of());

            // 循环保护检查（fastclaw：执行前检查，触发即 break 进入最终交付；
            // 已入历史的 tool calls 由 buildRequest 的孤立剥离保证协议合法）
            for (ToolCall call : toolCalls.calls()) {
                String signature = call.name() + "#" + argumentsHash(call.arguments());
                if (signature.equals(lastSignature)) {
                    consecutiveCount++;
                } else {
                    consecutiveCount = 1;
                    lastSignature = signature;
                }
                if (consecutiveCount >= LOOP_PROTECTION_THRESHOLD) {
                    log.warn("[kernel] 循环保护触发，runId={}, tool={}", command.runId(), call.name());
                    loopProtectionTripped = true;
                    break;
                }
            }
            if (loopProtectionTripped) {
                break;
            }

            // 串行执行本轮全部 tool calls，逐个配对 tool result
            // （V2 方案 6.2：有意不并行，防写工具竞争 workspace）
            boolean roundAllFailed = !toolCalls.calls().isEmpty();
            for (ToolCall call : toolCalls.calls()) {
                ToolResult result = executeSafely(command, conversation, call, runDeadline);
                conversation.appendToolResult(call, result);
                sink.emit(new AgentEvent.ToolResultProduced(call.id(), call.name(), result.observation()));
                if (result.success()) {
                    roundAllFailed = false;
                }
            }
            allFailedRounds = roundAllFailed ? allFailedRounds + 1 : 0;
        }

        // 迭代上限 / 循环保护：禁用工具做最终总结（LIMIT_REACHED 终态）
        return finalDelivery(command, conversation, sink, iterations, loopProtectionTripped);
    }

    /**
     * 统一兜底执行：Invoker 已包装超时与异常映射，此处兜住实现漏网的
     * RuntimeException，合成失败结果保证 tool call 配对闭合
     * （V2 方案 6.1 规则 8 / fastclaw defensive backstop）
     */
    private ToolResult executeSafely(
            AgentRunCommand command, AgentConversation conversation, ToolCall call, Instant runDeadline) {
        try {
            ToolExecutionContext context = new ToolExecutionContext(
                    command.runId(),
                    command.userId(),
                    command.agentId(),
                    command.sessionId(),
                    conversation.workspace(),
                    earlier(runDeadline, Instant.now().plus(command.config().toolTimeout())));
            ToolResult result = toolInvoker.invoke(call, context);
            if (result == null) {
                return ToolResult.failure(
                        ToolErrorCode.TOOL_RESULT_MISSING, "tool execution did not return a result");
            }
            return result;
        } catch (RuntimeException error) {
            log.warn("[kernel] 工具执行异常逃逸，合成失败结果，runId={}, tool={}",
                    command.runId(), call.name(), error);
            return ToolResult.failure(ToolErrorCode.TOOL_EXECUTION_FAILED, rootMessage(error));
        }
    }

    /**
     * 强制最终交付：追加 nudge、不携带 tools 再调用一次模型；
     * 总结失败才用固定兜底文本（fastclaw loop.go:2392-2411）
     */
    private AgentRunResult finalDelivery(
            AgentRunCommand command,
            AgentConversation conversation,
            AgentEventSink sink,
            int iterations,
            boolean loopProtectionTripped) {
        int max = command.config().maxToolIterations();
        log.warn("[kernel] {}，强制最终交付，runId={}, iterations={}",
                loopProtectionTripped ? "循环保护触发" : "达到迭代上限", command.runId(), iterations);
        List<ModelMessage> notes = loopProtectionTripped
                ? List.of(ModelMessage.system(
                        "Loop detected: you called the same tool with the same arguments "
                                + LOOP_PROTECTION_THRESHOLD + " times. Tools are now disabled for this "
                                + "final response. Synthesize what you've already gathered and answer directly."),
                        capReachedNudge(max))
                : List.of(capReachedNudge(max));

        String finalContent;
        try {
            ModelResponse response = llmService.stream(
                    conversation.buildRequest(List.of(), notes),
                    event -> {
                        if (event instanceof ModelEvent.TextDelta delta) {
                            sink.emit(new AgentEvent.ContentDelta(delta.text()));
                        }
                    });
            finalContent = response instanceof ModelResponse.Text text
                    ? text.content()
                    : ((ModelResponse.ToolCalls) response).content();
        } catch (RuntimeException error) {
            log.warn("[kernel] 最终总结调用失败，使用兜底文本，runId={}", command.runId(), error);
            finalContent = "";
        }
        if (finalContent.isBlank()) {
            // fastclaw 兜底文本语义
            finalContent = "I've reached the maximum number of tool iterations (" + max
                    + ") and couldn't synthesize a final response. The work above represents "
                    + "what I gathered before hitting the limit.";
        }
        Map<String, Object> capMetadata = Map.of(
                "iterationCapReached", true,
                "iterationCapValue", max);
        conversation.appendAssistant(finalContent, List.of(), "", capMetadata);
        sink.emit(new AgentEvent.Content(finalContent, capMetadata));
        return new AgentRunResult(
                command.runId(), AgentRunStatus.LIMIT_REACHED, finalContent, iterations, "", "");
    }

    private AgentRunResult timedOut(AgentRunCommand command, AgentEventSink sink, int iterations) {
        log.warn("[kernel] 运行总超时，runId={}, iterations={}", command.runId(), iterations);
        String message = "Agent run timed out after " + command.config().runTimeout();
        sink.emit(new AgentEvent.RunFailed(message));
        return new AgentRunResult(
                command.runId(), AgentRunStatus.TIMED_OUT, "", iterations, "RUN_TIMEOUT", message);
    }

    /**
     * 迭代上限 nudge（fastclaw capReachedNudge 语义）
     */
    private static ModelMessage capReachedNudge(int maxIterations) {
        return ModelMessage.system(
                "You've used all " + maxIterations + " tool-call iterations available for this turn. "
                        + "Tools are now disabled for this final response — do not attempt to call any. "
                        + "Synthesize what you've already gathered into the most complete deliverable you can. "
                        + "For any fields you couldn't resolve, mark them as 'unknown' / 'not found' / 'partial' "
                        + "rather than dropping them — give the user something usable plus an honest note "
                        + "about what's missing. Do not apologize without delivering content.");
    }

    /**
     * arguments 摘要（fastclaw 用 sha256 对比，等价实现）
     */
    private static String argumentsHash(String arguments) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Arrays.toString(digest.digest(arguments.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            // SHA-256 为 JVM 必备算法，不可达；兜底退回原文对比
            return arguments;
        }
    }

    private static Instant earlier(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
