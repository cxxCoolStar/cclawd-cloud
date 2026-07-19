package ai.openagent.bootstrap.agentrun.trace;

import ai.openagent.agent.AgentRunStatus;
import ai.openagent.bootstrap.agentrun.ToolExecutionStatus;
import java.util.List;

/**
 * 单次运行的完整轨迹（EVALUATION_PLAN.md Phase 1.2 JSON Trace 格式）
 *
 * <p>
 * 首版由 agent_runs + tool_executions 组装：工具轨迹 + 终态 + token 用量 +
 * 耗时，满足 Phase 2 确定性评分器与人工排查；模型中间响应文本
 * （session_events 回放）留给 Phase 6 产品化
 * </p>
 */
public record TraceVO(
        String runId,
        String agentId,
        String sessionId,
        AgentRunStatus status,
        int toolIterations,
        String errorCode,
        String errorMessage,
        long inputTokens,
        long outputTokens,
        long cacheReadTokens,
        long cacheWriteTokens,
        long startedAt,
        Long completedAt,
        Long durationMs,
        List<ToolEventVO> events) {

    /**
     * 一次工具执行事件（按 sequence 升序）
     *
     * @param arguments 工具入参 JSON（疑似密钥键的值已打码）
     */
    public record ToolEventVO(
            int sequence,
            String toolCallId,
            String toolName,
            String arguments,
            ToolExecutionStatus status,
            String resultContent,
            String errorCode,
            String errorMessage,
            long durationMs,
            long createdAt,
            Long completedAt) {}
}
