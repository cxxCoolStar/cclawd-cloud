package ai.openagent.agent;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

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
        AgentRuntimeConfig config,
        AgentConversationScope conversationScope,
        Path workspacePathOverride) {

    public AgentRunCommand {
        runId = Objects.requireNonNull(runId, "runId");
        userId = Objects.requireNonNull(userId, "userId");
        agentId = Objects.requireNonNull(agentId, "agentId");
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        userMessage = Objects.requireNonNull(userMessage, "userMessage");
        config = Objects.requireNonNull(config, "config");
    }

    /**
     * 构造命令（不带工作空间覆盖，使用默认路径）
     */
    public AgentRunCommand(
            String runId,
            String userId,
            String agentId,
            String sessionId,
            String userMessage,
            AgentRuntimeConfig config) {
        this(runId, userId, agentId, sessionId, userMessage, config, null, null);
    }

    public AgentRunCommand(
            String runId,
            String userId,
            String agentId,
            String sessionId,
            String userMessage,
            AgentRuntimeConfig config,
            Path workspacePathOverride) {
        this(runId, userId, agentId, sessionId, userMessage, config, null, workspacePathOverride);
    }

    /**
     * 获取工作空间路径覆盖（如果指定了）
     */
    public Optional<Path> getWorkspacePathOverride() {
        return Optional.ofNullable(workspacePathOverride);
    }

    public Optional<AgentConversationScope> getConversationScope() {
        return Optional.ofNullable(conversationScope);
    }
}
