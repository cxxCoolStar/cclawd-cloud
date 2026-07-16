package ai.openagent.bootstrap.persistence;

/**
 * Agent 工具配置持久化记录（agent_tools 表，主键 (agentId, toolName)）
 *
 * @param agentId    智能体 ID
 * @param toolName   工具名称
 * @param enabled    是否启用
 * @param configJson 工具非敏感配置 JSON（敏感凭证只允许环境变量注入）
 * @param createdAt  创建时间（epoch 毫秒）
 * @param updatedAt  更新时间（epoch 毫秒）
 */
public record AgentToolRecord(
        String agentId, String toolName, boolean enabled, String configJson, long createdAt, long updatedAt) {}
