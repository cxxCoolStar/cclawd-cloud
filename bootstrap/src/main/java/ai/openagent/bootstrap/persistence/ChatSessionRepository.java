package ai.openagent.bootstrap.persistence;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 聊天会话聚合仓储（sessions / session_messages / session_events）
 *
 * <p>
 * seq 生成对齐 fastclaw store 的原子模式：在单条
 * {@code INSERT ... SELECT COALESCE(MAX(seq), 0) + 1} 内完成分配，
 * 由数据库写序列化保证并发安全（SQLite 全局写锁 / PostgreSQL MVCC +
 * 唯一约束兜底），不再依赖 JVM 侧 synchronized；
 * {@code RETURNING seq} 取回分配值，避免第二次查询的竞态
 * </p>
 */
@Repository
@RequiredArgsConstructor
public class ChatSessionRepository {

    private final JdbcTemplate jdbc;

    /**
     * 确保会话存在：不存在则创建，存在则刷新预览与更新时间
     */
    @Transactional
    public void ensureSession(String userId, String agentId, String sessionId, String firstMessage) {
        long now = System.currentTimeMillis();
        String preview = preview(firstMessage, 240);
        int updated = jdbc.update(
                "UPDATE sessions SET preview = ?, updated_at = ? WHERE user_id = ? AND agent_id = ? AND id = ?",
                preview,
                now,
                userId,
                agentId,
                sessionId);
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO sessions (id, user_id, agent_id, title, preview, channel, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    sessionId,
                    userId,
                    agentId,
                    preview(firstMessage, 60),
                    preview,
                    "web",
                    now,
                    now);
        }
    }

    /**
     * 查询用户在指定 agent 下的会话列表（按更新时间倒序）
     */
    public List<ChatSessionRecord> listSessions(String userId, String agentId) {
        return jdbc.query(
                "SELECT id, title, preview, channel, created_at, updated_at FROM sessions WHERE user_id = ? AND agent_id = ? ORDER BY updated_at DESC",
                (rs, row) -> new ChatSessionRecord(
                        rs.getString("id"),
                        rs.getString("title"),
                        rs.getString("preview"),
                        rs.getString("channel"),
                        rs.getLong("created_at"),
                        rs.getLong("updated_at")),
                userId,
                agentId);
    }

    /**
     * 追加会话消息并刷新会话预览
     *
     * <p>
     * seq 在 INSERT 内原子分配（会话内单调递增，从 1 开始），
     * 并发追加由唯一约束 (user_id, agent_id, session_id, seq) 兜底
     * </p>
     */
    @Transactional
    public ChatMessageRecord appendMessage(
            String userId,
            String agentId,
            String sessionId,
            String role,
            String content,
            String provider,
            String model) {
        return appendMessage(userId, agentId, sessionId, role, content, provider, model, "", "", "");
    }

    /**
     * 追加带工具字段的会话消息（tool 消息以 toolCallId 配对；
     * assistant 消息的 metadataJson 携带 tool_calls 与 UI metadata）
     */
    @Transactional
    public ChatMessageRecord appendMessage(
            String userId,
            String agentId,
            String sessionId,
            String role,
            String content,
            String provider,
            String model,
            String toolCallId,
            String toolName,
            String metadataJson) {
        long now = System.currentTimeMillis();
        Long seq = jdbc.queryForObject(
                """
                INSERT INTO session_messages (id, user_id, agent_id, session_id, seq, role, content, provider, model, tool_call_id, tool_name, metadata_json, created_at)
                SELECT ?, ?, ?, ?, COALESCE(MAX(seq), 0) + 1, ?, ?, ?, ?, ?, ?, ?, ?
                  FROM session_messages
                 WHERE user_id = ? AND agent_id = ? AND session_id = ?
                RETURNING seq
                """,
                Long.class,
                UUID.randomUUID().toString(),
                userId,
                agentId,
                sessionId,
                role,
                content,
                value(provider),
                value(model),
                value(toolCallId),
                value(toolName),
                value(metadataJson),
                now,
                userId,
                agentId,
                sessionId);
        jdbc.update(
                "UPDATE sessions SET preview = ?, updated_at = ? WHERE user_id = ? AND agent_id = ? AND id = ?",
                preview(content, 240),
                now,
                userId,
                agentId,
                sessionId);
        return new ChatMessageRecord(
                seq == null ? 1 : seq, role, content, value(provider), value(model),
                value(toolCallId), value(toolName), value(metadataJson), now);
    }

    /**
     * 查询会话完整消息历史（按 seq 升序）
     */
    public List<ChatMessageRecord> listMessages(String userId, String agentId, String sessionId) {
        return jdbc.query(
                "SELECT seq, role, content, provider, model, tool_call_id, tool_name, metadata_json, created_at FROM session_messages WHERE user_id = ? AND agent_id = ? AND session_id = ? ORDER BY seq",
                (rs, row) -> new ChatMessageRecord(
                        rs.getLong("seq"),
                        rs.getString("role"),
                        rs.getString("content"),
                        rs.getString("provider"),
                        rs.getString("model"),
                        rs.getString("tool_call_id"),
                        rs.getString("tool_name"),
                        rs.getString("metadata_json"),
                        rs.getLong("created_at")),
                userId,
                agentId,
                sessionId);
    }

    /**
     * 追加持久化事件并返回分配的 seq（会话内单调递增，供断线回放定位）
     */
    @Transactional
    public SessionEventRecord appendEvent(
            String userId, String agentId, String sessionId, String eventType, String eventData) {
        Long seq = jdbc.queryForObject(
                """
                INSERT INTO session_events (id, user_id, agent_id, session_id, seq, event_type, event_data, created_at)
                SELECT ?, ?, ?, ?, COALESCE(MAX(seq), 0) + 1, ?, ?, ?
                  FROM session_events
                 WHERE user_id = ? AND agent_id = ? AND session_id = ?
                RETURNING seq
                """,
                Long.class,
                UUID.randomUUID().toString(),
                userId,
                agentId,
                sessionId,
                eventType,
                eventData,
                System.currentTimeMillis(),
                userId,
                agentId,
                sessionId);
        return new SessionEventRecord(seq == null ? 1 : seq, eventType, eventData);
    }

    /**
     * 查询指定序号之后的持久化事件（断线重连回放）
     */
    public List<SessionEventRecord> listEventsSince(String userId, String agentId, String sessionId, long since) {
        return jdbc.query(
                "SELECT seq, event_type, event_data FROM session_events WHERE user_id = ? AND agent_id = ? AND session_id = ? AND seq > ? ORDER BY seq",
                (rs, row) -> new SessionEventRecord(
                        rs.getLong("seq"), rs.getString("event_type"), rs.getString("event_data")),
                userId,
                agentId,
                sessionId,
                since);
    }

    /**
     * 查询会话最新事件序号（无事件时为 -1，前端 resume 游标）
     */
    public long latestEventSequence(String userId, String agentId, String sessionId) {
        Long value = jdbc.queryForObject(
                "SELECT COALESCE(MAX(seq), -1) FROM session_events WHERE user_id = ? AND agent_id = ? AND session_id = ?",
                Long.class,
                userId,
                agentId,
                sessionId);
        return value == null ? -1 : value;
    }

    private static String preview(String text, int maxLength) {
        String normalized = value(text).replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
