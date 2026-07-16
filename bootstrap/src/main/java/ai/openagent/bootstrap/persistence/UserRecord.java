package ai.openagent.bootstrap.persistence;

/**
 * 用户持久化记录
 *
 * @param id          用户 ID
 * @param username    用户名
 * @param email       邮箱
 * @param role        角色（super_admin 等）
 * @param displayName 显示名
 * @param status      状态（active 等）
 * @param createdAt   创建时间（epoch 毫秒）
 */
public record UserRecord(
        String id, String username, String email, String role, String displayName, String status, long createdAt) {}
