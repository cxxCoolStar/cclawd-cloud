package ai.openagent.agent;

import java.util.Objects;

/**
 * Agent 运行的最终结果（V2 方案 7.1）
 *
 * @param runId          运行 ID
 * @param status         终态（COMPLETED/FAILED/TIMED_OUT/LIMIT_REACHED）
 * @param finalContent   最终回答文本
 * @param toolIterations 实际执行的工具迭代轮数
 * @param errorCode      终态错误码，可空
 * @param errorMessage   清理后的错误信息，可空
 */
public record AgentRunResult(
        String runId,
        AgentRunStatus status,
        String finalContent,
        int toolIterations,
        String errorCode,
        String errorMessage) {

    public AgentRunResult {
        runId = Objects.requireNonNull(runId, "runId");
        status = Objects.requireNonNull(status, "status");
        finalContent = Objects.requireNonNullElse(finalContent, "");
        errorCode = Objects.requireNonNullElse(errorCode, "");
        errorMessage = Objects.requireNonNullElse(errorMessage, "");
    }
}
