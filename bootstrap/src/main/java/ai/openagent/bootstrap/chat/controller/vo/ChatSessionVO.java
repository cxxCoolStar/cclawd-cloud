package ai.openagent.bootstrap.chat.controller.vo;

import ai.openagent.bootstrap.persistence.ChatSessionRecord;

/**
 * 会话摘要视图对象
 *
 * @param id        会话 ID
 * @param title     会话标题（取首条消息截断）
 * @param preview   最近消息预览
 * @param channel   来源渠道（web 等）
 * @param createdAt 创建时间（epoch 毫秒）
 * @param updatedAt 更新时间（epoch 毫秒）
 */
public record ChatSessionVO(
        String id, String title, String preview, String channel, long createdAt, long updatedAt) {

    /**
     * 由持久化记录装配
     */
    public static ChatSessionVO from(ChatSessionRecord record) {
        return new ChatSessionVO(
                record.id(),
                record.title(),
                record.preview(),
                record.channel(),
                record.createdAt(),
                record.updatedAt());
    }
}
