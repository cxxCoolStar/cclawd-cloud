package ai.openagent.bootstrap.identity.controller.vo;

import java.util.List;

/**
 * API Key 视图对象（对齐前端 apikeys 页契约）
 *
 * @param id         Key ID
 * @param userId     属主用户 ID
 * @param name       名称
 * @param key        打码后的 Key 展示串（明文只在创建响应的 token 字段出现一次）
 * @param type       前端契约类型：绑定 agent 子集为 agent，否则为 user
 * @param agents     绑定的 agent 子集（空 = 不限制）
 * @param createdAt  创建时间（epoch 毫秒）
 * @param lastUsedAt 最近使用时间（epoch 毫秒，未使用过为 null）
 */
public record ApiKeyVO(
        String id,
        String userId,
        String name,
        String key,
        String type,
        List<String> agents,
        long createdAt,
        Long lastUsedAt) {}
