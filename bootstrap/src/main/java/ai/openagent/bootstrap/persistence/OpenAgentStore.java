package ai.openagent.bootstrap.persistence;

import ai.openagent.bootstrap.config.ModelSettings;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class OpenAgentStore {

    public static final String LOCAL_USER_ID = "local-user";
    public static final String DEFAULT_AGENT_ID = "default";
    public static final String DEFAULT_PROVIDER_ID = "default-provider";

    private final JdbcTemplate jdbc;

    public OpenAgentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void seedDefaults(ModelSettings settings) {
        long now = System.currentTimeMillis();
        if (count("users", "id", LOCAL_USER_ID) == 0) {
            jdbc.update(
                    "INSERT INTO users (id, username, email, role, display_name, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    LOCAL_USER_ID,
                    "local",
                    "local@openagent.invalid",
                    "super_admin",
                    "Local User",
                    "active",
                    now);
        }
        if (count("providers", "id", DEFAULT_PROVIDER_ID) == 0) {
            jdbc.update(
                    "INSERT INTO providers (id, provider_type, name, api_base, api_key, model, temperature, max_tokens, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    DEFAULT_PROVIDER_ID,
                    settings.provider(),
                    "Default Provider",
                    settings.apiBase(),
                    value(settings.apiKey()),
                    settings.name(),
                    settings.temperature(),
                    settings.maxTokens(),
                    now,
                    now);
        } else {
            jdbc.update(
                    "UPDATE providers SET provider_type = ?, api_base = ?, api_key = ?, model = ?, temperature = ?, max_tokens = ?, updated_at = ? WHERE id = ?",
                    settings.provider(),
                    settings.apiBase(),
                    value(settings.apiKey()),
                    settings.name(),
                    settings.temperature(),
                    settings.maxTokens(),
                    now,
                    DEFAULT_PROVIDER_ID);
        }
        if (count("agents", "id", DEFAULT_AGENT_ID) == 0) {
            jdbc.update(
                    "INSERT INTO agents (id, user_id, name, description, provider_id, model, system_prompt, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    DEFAULT_AGENT_ID,
                    LOCAL_USER_ID,
                    "OpenAgent",
                    "Default local chatbot",
                    DEFAULT_PROVIDER_ID,
                    settings.name(),
                    settings.systemPrompt(),
                    now,
                    now);
        } else {
            jdbc.update(
                    "UPDATE agents SET provider_id = ?, model = ?, system_prompt = ?, updated_at = ? WHERE id = ?",
                    DEFAULT_PROVIDER_ID,
                    settings.name(),
                    settings.systemPrompt(),
                    now,
                    DEFAULT_AGENT_ID);
        }
    }

    public List<AgentRecord> listAgents(String userId) {
        return jdbc.query(
                "SELECT id, user_id, name, description, provider_id, model, system_prompt, created_at, updated_at FROM agents WHERE user_id = ? ORDER BY created_at",
                (rs, row) -> new AgentRecord(
                        rs.getString("id"),
                        rs.getString("user_id"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getString("provider_id"),
                        rs.getString("model"),
                        rs.getString("system_prompt"),
                        rs.getLong("created_at"),
                        rs.getLong("updated_at")),
                userId);
    }

    public Optional<AgentRecord> findAgent(String id) {
        return jdbc.query(
                        "SELECT id, user_id, name, description, provider_id, model, system_prompt, created_at, updated_at FROM agents WHERE id = ?",
                        (rs, row) -> new AgentRecord(
                                rs.getString("id"),
                                rs.getString("user_id"),
                                rs.getString("name"),
                                rs.getString("description"),
                                rs.getString("provider_id"),
                                rs.getString("model"),
                                rs.getString("system_prompt"),
                                rs.getLong("created_at"),
                                rs.getLong("updated_at")),
                        id)
                .stream()
                .findFirst();
    }

    public Optional<ProviderRecord> findProvider(String id) {
        return jdbc.query(
                        "SELECT id, provider_type, name, api_base, api_key, model, temperature, max_tokens FROM providers WHERE id = ?",
                        (rs, row) -> new ProviderRecord(
                                rs.getString("id"),
                                rs.getString("provider_type"),
                                rs.getString("name"),
                                rs.getString("api_base"),
                                rs.getString("api_key"),
                                rs.getString("model"),
                                rs.getDouble("temperature"),
                                rs.getInt("max_tokens")),
                        id)
                .stream()
                .findFirst();
    }

    @Transactional
    public synchronized void ensureSession(String userId, String agentId, String sessionId, String firstMessage) {
        long now = System.currentTimeMillis();
        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sessions WHERE user_id = ? AND agent_id = ? AND id = ?",
                Integer.class,
                userId,
                agentId,
                sessionId);
        String preview = preview(firstMessage, 240);
        if (existing == null || existing == 0) {
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
        } else {
            jdbc.update(
                    "UPDATE sessions SET preview = ?, updated_at = ? WHERE user_id = ? AND agent_id = ? AND id = ?",
                    preview,
                    now,
                    userId,
                    agentId,
                    sessionId);
        }
    }

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

    @Transactional
    public synchronized ChatMessageRecord appendMessage(
            String userId,
            String agentId,
            String sessionId,
            String role,
            String content,
            String provider,
            String model) {
        long seq = nextSequence("session_messages", userId, agentId, sessionId);
        long now = System.currentTimeMillis();
        jdbc.update(
                "INSERT INTO session_messages (id, user_id, agent_id, session_id, seq, role, content, provider, model, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(),
                userId,
                agentId,
                sessionId,
                seq,
                role,
                content,
                value(provider),
                value(model),
                now);
        jdbc.update(
                "UPDATE sessions SET preview = ?, updated_at = ? WHERE user_id = ? AND agent_id = ? AND id = ?",
                preview(content, 240),
                now,
                userId,
                agentId,
                sessionId);
        return new ChatMessageRecord(seq, role, content, value(provider), value(model), now);
    }

    public List<ChatMessageRecord> listMessages(String userId, String agentId, String sessionId) {
        return jdbc.query(
                "SELECT seq, role, content, provider, model, created_at FROM session_messages WHERE user_id = ? AND agent_id = ? AND session_id = ? ORDER BY seq",
                (rs, row) -> new ChatMessageRecord(
                        rs.getLong("seq"),
                        rs.getString("role"),
                        rs.getString("content"),
                        rs.getString("provider"),
                        rs.getString("model"),
                        rs.getLong("created_at")),
                userId,
                agentId,
                sessionId);
    }

    @Transactional
    public synchronized SessionEventRecord appendEvent(
            String userId, String agentId, String sessionId, String eventType, String eventData) {
        long seq = nextSequence("session_events", userId, agentId, sessionId);
        jdbc.update(
                "INSERT INTO session_events (id, user_id, agent_id, session_id, seq, event_type, event_data, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(),
                userId,
                agentId,
                sessionId,
                seq,
                eventType,
                eventData,
                System.currentTimeMillis());
        return new SessionEventRecord(seq, eventType, eventData);
    }

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

    public long latestEventSequence(String userId, String agentId, String sessionId) {
        Long value = jdbc.queryForObject(
                "SELECT COALESCE(MAX(seq), -1) FROM session_events WHERE user_id = ? AND agent_id = ? AND session_id = ?",
                Long.class,
                userId,
                agentId,
                sessionId);
        return value == null ? -1 : value;
    }

    private int count(String table, String column, String value) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, value);
        return count == null ? 0 : count;
    }

    private long nextSequence(String table, String userId, String agentId, String sessionId) {
        Long current = jdbc.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) FROM " + table
                        + " WHERE user_id = ? AND agent_id = ? AND session_id = ?",
                Long.class,
                userId,
                agentId,
                sessionId);
        return (current == null ? 0 : current) + 1;
    }

    private static String preview(String text, int maxLength) {
        String normalized = value(text).replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
