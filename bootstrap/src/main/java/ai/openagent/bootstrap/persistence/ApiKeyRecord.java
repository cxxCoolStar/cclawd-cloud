package ai.openagent.bootstrap.persistence;

/**
 * API Key 持久化记录
 *
 * @param id         Key ID
 * @param userId     属主用户 ID
 * @param name       名称
 * @param keyHash    Key 明文的 SHA-256 散列（hex）；明文不落库
 * @param agentIdsJson 绑定的 agent 子集（JSON 数组，空数组 = 不限制）
 * @param createdAt  创建时间（epoch 毫秒）
 * @param lastUsedAt 最近使用时间（epoch 毫秒，未使用过为 null）
 */
public record ApiKeyRecord(
        String id,
        String userId,
        String name,
        String keyHash,
        String agentIdsJson,
        long createdAt,
        Long lastUsedAt) {}
