package ai.openagent.bootstrap.chat.controller.vo;

import ai.openagent.bootstrap.persistence.ChatMessageRecord;

/**
 * 历史消息视图对象
 *
 * @param role    消息角色（user / assistant）
 * @param content 消息内容
 */
public record ChatMessageVO(String role, String content) {

    /**
     * 由持久化记录装配
     */
    public static ChatMessageVO from(ChatMessageRecord record) {
        return new ChatMessageVO(record.role(), record.content());
    }
}
