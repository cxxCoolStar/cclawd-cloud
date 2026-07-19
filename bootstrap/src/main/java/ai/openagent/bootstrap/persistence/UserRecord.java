package ai.openagent.bootstrap.persistence;

/**
 * 用户持久化记录
 *
 * @param id           用户 ID
 * @param username     用户名
 * @param email        邮箱
 * @param role         角色（super_admin / user）
 * @param displayName  显示名
 * @param status       状态（active 等）
 * @param passwordHash BCrypt 密码散列；空串表示未设密码（不可登录）
 * @param avatarUrl    头像地址
 * @param agentQuota   智能体配额（-1 表示不限制，V9 M2 预留字段）
 * @param createdAt    创建时间（epoch 毫秒）
 */
public record UserRecord(
        String id,
        String username,
        String email,
        String role,
        String displayName,
        String status,
        String passwordHash,
        String avatarUrl,
        int agentQuota,
        long createdAt) {}
