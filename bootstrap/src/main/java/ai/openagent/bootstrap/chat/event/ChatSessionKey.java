package ai.openagent.bootstrap.chat.event;

/**
 * 聊天会话键值对象
 *
 * <p>
 * 以 (agentId, sessionId) 唯一标识一路聊天会话，统一
 * {@link ChatEventHub} 的订阅路由与并发回合锁的键格式，
 * 避免各处重复手拼字符串键
 * </p>
 *
 * @param agentId   智能体 ID
 * @param sessionId 会话 ID
 */
public record ChatSessionKey(String agentId, String sessionId) {

    /**
     * 生成用于 Map 键的紧凑字符串（换行符不会出现在两个 ID 中，保证无歧义）
     */
    public String compact() {
        return agentId + "\n" + sessionId;
    }
}
