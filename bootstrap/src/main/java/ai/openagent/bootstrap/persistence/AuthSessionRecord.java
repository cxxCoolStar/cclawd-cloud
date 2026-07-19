package ai.openagent.bootstrap.persistence;

/**
 * 登录会话持久化记录
 *
 * @param token     会话令牌（cookie 值，主键）
 * @param userId    所属用户 ID
 * @param createdAt 创建时间（epoch 毫秒）
 * @param expiresAt 过期时间（epoch 毫秒）
 */
public record AuthSessionRecord(String token, String userId, long createdAt, long expiresAt) {}
