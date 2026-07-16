package ai.openagent.bootstrap.persistence;

import ai.openagent.bootstrap.agentrun.ToolExecutionStatus;

/**
 * 工具执行持久化记录（tool_executions 表）
 *
 * @param id            内部主键
 * @param runId         所属 Agent run
 * @param toolCallId    模型返回的 Tool Call ID（run 内唯一）
 * @param sequence      同一 run 内执行顺序（从 1 开始）
 * @param toolName      工具名称
 * @param argumentsJson 清理后的参数 JSON
 * @param status        执行状态
 * @param resultContent 截断后的工具结果（可空）
 * @param errorCode     工具错误码（可空）
 * @param errorMessage  清理后的错误信息（可空）
 * @param durationMs    执行耗时（毫秒）
 * @param createdAt     创建时间（epoch 毫秒）
 * @param completedAt   完成时间（epoch 毫秒，可空）
 */
public record ToolExecutionRecord(
        String id,
        String runId,
        String toolCallId,
        int sequence,
        String toolName,
        String argumentsJson,
        ToolExecutionStatus status,
        String resultContent,
        String errorCode,
        String errorMessage,
        long durationMs,
        long createdAt,
        Long completedAt) {}
