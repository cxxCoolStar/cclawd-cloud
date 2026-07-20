package ai.openagent.bootstrap.agent.controller.vo;

import ai.openagent.bootstrap.persistence.AgentRecord;

/**
 * 智能体视图对象
 *
 * <p>
 * role/workspace/isPublic/shareModelConfig 等字段当前为单用户模式下的
 * 固定值，多用户与工作区能力落地后由真实数据填充
 * </p>
 *
 * @param id               智能体 ID
 * @param name             名称
 * @param description      描述
 * @param userId           属主用户 ID
 * @param role             调用者对该 agent 的角色（单用户模式恒为 owner）
 * @param model            使用的模型
 * @param workspace        工作区路径（未落地，恒为空）
 * @param avatarUrl        头像地址
 * @param createdAt        创建时间（epoch 毫秒）
 * @param isPublic         是否公开（未落地，恒 false）
 * @param shareModelConfig 是否共享模型配置（未落地，恒 false）
 */
public record AgentVO(
        String id,
        String name,
        String description,
        String userId,
        String role,
        String model,
        String workspace,
        String avatarUrl,
        long createdAt,
        boolean isPublic,
        boolean shareModelConfig) {

    /**
     * 由持久化记录装配
     */
    public static AgentVO from(AgentRecord agent) {
        return new AgentVO(
                agent.id(),
                agent.name(),
                agent.description(),
                agent.userId(),
                "owner",
                agent.model(),
                "",
                "/api/agents/" + agent.id() + "/files/avatar.png",
                agent.createdAt(),
                false,
                false);
    }
}
