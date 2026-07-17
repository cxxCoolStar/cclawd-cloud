package ai.openagent.agent;

import java.util.Objects;

/**
 * 一次 Agent 运行命令（V2 方案 7.1）
 *
 * <p>
 * V2 本地单用户模式下 userId 固定为 local-user，但字段保留为
 * 数据边界（V2 方案第 10 章：不能为了本地模式删除关联字段）
 * </p>
 */
public record AgentRunCommand(
        String runId,
        String userId,
        String agentId,
        String sessionId,
        String userMessage,
        AgentRuntimeConfig config) {

    public AgentRunCommand {
        runId = Objects.requireNonNull(runId, "runId");
        userId = Objects.requireNonNull(userId, "userId");
        agentId = Objects.requireNonNull(agentId, "agentId");
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        userMessage = Objects.requireNonNull(userMessage, "userMessage");
        config = Objects.requireNonNull(config, "config");
    }
}
