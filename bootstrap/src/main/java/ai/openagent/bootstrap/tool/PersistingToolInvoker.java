package ai.openagent.bootstrap.tool;

import ai.openagent.agent.tool.AgentTool;
import ai.openagent.agent.tool.ToolArguments;
import ai.openagent.agent.tool.ToolErrorCode;
import ai.openagent.agent.tool.ToolExecutionContext;
import ai.openagent.agent.tool.ToolInvoker;
import ai.openagent.agent.tool.ToolRegistry;
import ai.openagent.agent.tool.ToolResult;
import ai.openagent.agent.tool.ToolUnavailableException;
import ai.openagent.bootstrap.agentrun.ToolExecutionStatus;
import ai.openagent.bootstrap.persistence.ToolExecutionRepository;
import ai.openagent.bootstrap.tool.config.ToolProperties;
import ai.openagent.infra.ai.model.ToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 工具统一调用入口实现（V2 方案 3.1 工具框架）
 *
 * <p>
 * 统一包装：白名单/启停校验 → 参数 JSON 合法性 → 独立线程池执行 +
 * 截止时间超时（超时取消 Future）→ 结果截断 → tool_executions 全程
 * 持久化（REQUESTED → RUNNING → 终态）。任何失败都映射为失败
 * ToolResult 回传（失败是 observation，不是异常）；日志只记录工具名、
 * 状态与耗时，不打印参数与完整结果（V2 方案第 14 章）
 * </p>
 */
@Slf4j
@Component
public class PersistingToolInvoker implements ToolInvoker {

    private final ToolRegistry toolRegistry;
    private final ToolExecutionRepository executionRepository;
    private final ToolProperties toolProperties;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;

    public PersistingToolInvoker(
            ToolRegistry toolRegistry,
            ToolExecutionRepository executionRepository,
            ToolProperties toolProperties,
            ObjectMapper objectMapper,
            @Qualifier("toolExecutor") ExecutorService executor) {
        this.toolRegistry = toolRegistry;
        this.executionRepository = executionRepository;
        this.toolProperties = toolProperties;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    @Override
    public ToolResult invoke(ToolCall call, ToolExecutionContext context) {
        long startedAt = System.currentTimeMillis();
        String executionId = UUID.randomUUID().toString();
        executionRepository.insertRequested(
                executionId, context.runId(), call.id(), call.name(), call.arguments());
        log.info("[tool] 工具执行开始，runId={}, tool={}, toolCallId={}",
                context.runId(), call.name(), call.id());

        ToolResult result = doInvoke(call, context);
        result = truncate(result).withDuration(System.currentTimeMillis() - startedAt);

        ToolExecutionStatus status = result.success()
                ? ToolExecutionStatus.SUCCEEDED
                : ToolErrorCode.TOOL_TIMEOUT.equals(result.errorCode())
                        ? ToolExecutionStatus.TIMED_OUT
                        : ToolExecutionStatus.FAILED;
        executionRepository.complete(
                executionId, status, result.content(), result.errorCode(), result.errorMessage(),
                result.durationMs());
        log.info("[tool] 工具执行完成，runId={}, tool={}, status={}, 结果长度={}, truncated={}, 耗时 {}ms",
                context.runId(), call.name(), status, result.content().length(), result.truncated(),
                result.durationMs());
        return result;
    }

    private ToolResult doInvoke(ToolCall call, ToolExecutionContext context) {
        // 白名单与启停校验
        AgentTool tool;
        try {
            tool = toolRegistry.requireEnabled(context.agentId(), call.name());
        } catch (ToolUnavailableException error) {
            return ToolResult.failure(error.errorCode(), error.getMessage());
        }
        // 参数 JSON 合法性（对象或空；非法 JSON 不进入工具实现）
        String argumentsJson = call.arguments().isBlank() ? "{}" : call.arguments();
        try {
            if (!objectMapper.readTree(argumentsJson).isObject()) {
                return ToolResult.failure(
                        ToolErrorCode.TOOL_ARGUMENT_INVALID, "tool arguments must be a JSON object");
            }
        } catch (Exception error) {
            return ToolResult.failure(
                    ToolErrorCode.TOOL_ARGUMENT_INVALID, "tool arguments is not valid JSON");
        }

        // 独立线程池执行 + 截止时间超时；超时取消 Future 并持久化 TIMED_OUT
        // （V2 方案 12.3；队列满快速失败，不占 Web/回合线程）
        long timeoutMs = Math.max(1, Duration.between(Instant.now(), context.deadline()).toMillis());
        Future<ToolResult> future;
        try {
            future = executor.submit(() -> tool.execute(new ToolArguments(argumentsJson), context));
        } catch (RejectedExecutionException error) {
            return ToolResult.failure(
                    ToolErrorCode.TOOL_EXECUTION_FAILED, "tool executor is busy, try again later");
        }
        try {
            ToolResult result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            if (result == null) {
                return ToolResult.failure(
                        ToolErrorCode.TOOL_RESULT_MISSING, "tool returned no result");
            }
            return result;
        } catch (TimeoutException error) {
            future.cancel(true);
            return ToolResult.failure(
                    ToolErrorCode.TOOL_TIMEOUT, "tool execution timed out after " + timeoutMs + "ms");
        } catch (ExecutionException error) {
            return ToolResult.failure(
                    ToolErrorCode.TOOL_EXECUTION_FAILED, rootMessage(error.getCause()));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return ToolResult.failure(ToolErrorCode.TOOL_EXECUTION_FAILED, "tool execution interrupted");
        }
    }

    /**
     * 结果截断（V2 方案 3.1：工具结果默认上限 64 KiB 字符）
     */
    private ToolResult truncate(ToolResult result) {
        int max = toolProperties.maxResultChars();
        if (result.content().length() <= max) {
            return result;
        }
        return result.truncatedTo(
                result.content().substring(0, max) + "\n... [truncated, " + result.content().length()
                        + " chars total]");
    }

    private static String rootMessage(Throwable error) {
        if (error == null) {
            return "unknown tool failure";
        }
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
