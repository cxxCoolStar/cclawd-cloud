package ai.openagent.bootstrap.persistence;

import ai.openagent.bootstrap.agentrun.AgentRunStatus;

/**
 * Agent 运行持久化记录（agent_runs 表）
 *
 * @param id             runId
 * @param userId         用户 ID（本地模式固定，为后续认证预留）
 * @param agentId        智能体 ID
 * @param sessionId      会话 ID
 * @param status         运行状态
 * @param toolIterations 已执行工具迭代数
 * @param errorCode      终态错误码（可空）
 * @param errorMessage   清理后的错误信息（可空）
 * @param startedAt      开始时间（epoch 毫秒）
 * @param completedAt    完成时间（epoch 毫秒，可空）
 * @param createdAt      创建时间（epoch 毫秒）
 * @param updatedAt      更新时间（epoch 毫秒）
 */
public record AgentRunRecord(
        String id,
        String userId,
        String agentId,
        String sessionId,
        AgentRunStatus status,
        int toolIterations,
        String errorCode,
        String errorMessage,
        long startedAt,
        Long completedAt,
        long createdAt,
        long updatedAt) {}
