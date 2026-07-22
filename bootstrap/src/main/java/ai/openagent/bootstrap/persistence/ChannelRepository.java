package ai.openagent.bootstrap.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Persists channel bindings, conversation mappings, and inbound idempotency keys. */
@Repository
@RequiredArgsConstructor
public class ChannelRepository {

    private final JdbcTemplate jdbc;

    public List<ChannelBindingRecord> listBindingsByUser(String userId) {
        return jdbc.query(
                "SELECT * FROM channel_bindings WHERE user_id = ? ORDER BY created_at",
                (rs, row) -> binding(rs),
                userId);
    }

    public List<ChannelBindingRecord> listBindings(String userId, String agentId) {
        return jdbc.query(
                "SELECT * FROM channel_bindings WHERE user_id = ? AND agent_id = ? ORDER BY created_at",
                (rs, row) -> binding(rs), userId, agentId);
    }

    public Optional<ChannelBindingRecord> findEnabledBinding(String channelType, String accountId) {
        return jdbc.query(
                        "SELECT * FROM channel_bindings WHERE channel_type = ? AND account_id = ? AND enabled = ?",
                        (rs, row) -> binding(rs), channelType, accountId, true)
                .stream().findFirst();
    }

    public void insertBinding(ChannelBindingRecord binding) {
        jdbc.update("""
                INSERT INTO channel_bindings
                    (id, user_id, agent_id, channel_type, account_id, display_name, credentials_json,
                     enabled, shared_identity, state_json, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                binding.id(), binding.userId(), binding.agentId(), binding.channelType(), binding.accountId(),
                binding.displayName(), binding.credentialsJson(), binding.enabled(), binding.sharedIdentity(),
                binding.stateJson(), binding.createdAt(), binding.updatedAt());
    }

    public List<ChannelBindingRecord> listEnabledBindings() {
        return jdbc.query(
                "SELECT * FROM channel_bindings WHERE enabled = ? ORDER BY created_at",
                (rs, row) -> binding(rs),
                true);
    }

    public Optional<ChannelBindingRecord> findOwnedBinding(
            String userId, String agentId, String channelType, String accountId) {
        return jdbc.query(
                        "SELECT * FROM channel_bindings WHERE user_id = ? AND agent_id = ? AND channel_type = ? AND account_id = ?",
                        (rs, row) -> binding(rs),
                        userId,
                        agentId,
                        channelType,
                        accountId)
                .stream()
                .findFirst();
    }

    @Transactional
    public void upsertBinding(ChannelBindingRecord binding) {
        int updated = jdbc.update("""
                UPDATE channel_bindings
                   SET user_id = ?, agent_id = ?, display_name = ?, credentials_json = ?,
                       enabled = ?, shared_identity = ?, state_json = ?, updated_at = ?
                 WHERE channel_type = ? AND account_id = ?
                """,
                binding.userId(), binding.agentId(), binding.displayName(), binding.credentialsJson(),
                binding.enabled(), binding.sharedIdentity(), binding.stateJson(), binding.updatedAt(),
                binding.channelType(), binding.accountId());
        if (updated == 0) {
            insertBinding(binding);
        }
    }

    @Transactional
    public boolean deleteBinding(String userId, String agentId, String channelType, String accountId) {
        Optional<ChannelBindingRecord> binding =
                findOwnedBinding(userId, agentId, channelType, accountId);
        if (binding.isEmpty()) {
            return false;
        }
        jdbc.update(
                "DELETE FROM channel_inbound_messages WHERE binding_id = ?",
                binding.get().id());
        jdbc.update(
                "DELETE FROM channel_conversations WHERE binding_id = ?",
                binding.get().id());
        return jdbc.update(
                "DELETE FROM channel_bindings WHERE id = ?",
                binding.get().id()) > 0;
    }

    public boolean updateSharedIdentity(
            String userId, String agentId, String channelType, String accountId, boolean sharedIdentity) {
        return jdbc.update("""
                UPDATE channel_bindings SET shared_identity = ?, updated_at = ?
                 WHERE user_id = ? AND agent_id = ? AND channel_type = ? AND account_id = ?
                """, sharedIdentity, System.currentTimeMillis(), userId, agentId, channelType, accountId) > 0;
    }

    public void updateState(String bindingId, String stateJson) {
        jdbc.update(
                "UPDATE channel_bindings SET state_json = ?, updated_at = ? WHERE id = ?",
                stateJson, System.currentTimeMillis(), bindingId);
    }

    @Transactional
    public ChannelConversationRecord resolveConversation(
            ChannelBindingRecord binding, String chatId, String chatterId, String contextToken) {
        Optional<ChannelConversationRecord> existing = findConversation(binding.id(), chatId);
        if (existing.isPresent()) {
            ChannelConversationRecord current = existing.get();
            long now = System.currentTimeMillis();
            jdbc.update("UPDATE channel_conversations SET chatter_id = ?, context_token = ?, updated_at = ? WHERE id = ?",
                    chatterId, value(contextToken), now, current.id());
            return new ChannelConversationRecord(
                    current.id(), current.bindingId(), current.chatId(), chatterId, current.sessionId(),
                    value(contextToken), current.createdAt(), now);
        }
        long now = System.currentTimeMillis();
        String id = UUID.randomUUID().toString();
        ChannelConversationRecord created = new ChannelConversationRecord(
                id, binding.id(), chatId, chatterId, "channel-" + id, value(contextToken), now, now);
        try {
            jdbc.update("""
                    INSERT INTO channel_conversations
                        (id, binding_id, chat_id, chatter_id, session_id, context_token, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, created.id(), created.bindingId(), created.chatId(), created.chatterId(),
                    created.sessionId(), created.contextToken(), created.createdAt(), created.updatedAt());
            return created;
        } catch (DataAccessException race) {
            return findConversation(binding.id(), chatId).orElseThrow(() -> race);
        }
    }

    public Optional<ChannelConversationRecord> findConversation(String bindingId, String chatId) {
        return jdbc.query(
                        "SELECT * FROM channel_conversations WHERE binding_id = ? AND chat_id = ?",
                        (rs, row) -> new ChannelConversationRecord(
                                rs.getString("id"), rs.getString("binding_id"), rs.getString("chat_id"),
                                rs.getString("chatter_id"), rs.getString("session_id"),
                                rs.getString("context_token"), rs.getLong("created_at"), rs.getLong("updated_at")),
                        bindingId, chatId)
                .stream().findFirst();
    }

    public boolean claimInbound(String bindingId, String messageId, String conversationId) {
        try {
            jdbc.update("""
                    INSERT INTO channel_inbound_messages
                        (binding_id, message_id, conversation_id, run_id, received_at)
                    VALUES (?, ?, ?, NULL, ?)
                    """, bindingId, messageId, conversationId, System.currentTimeMillis());
            return true;
        } catch (DataAccessException failure) {
            if (inboundExists(bindingId, messageId)) {
                return false;
            }
            throw failure;
        }
    }

    private boolean inboundExists(String bindingId, String messageId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM channel_inbound_messages WHERE binding_id = ? AND message_id = ?",
                Integer.class,
                bindingId,
                messageId);
        return count != null && count > 0;
    }

    public void releaseInbound(String bindingId, String messageId) {
        jdbc.update(
                "DELETE FROM channel_inbound_messages WHERE binding_id = ? AND message_id = ? AND run_id IS NULL",
                bindingId,
                messageId);
    }

    public void attachRun(String bindingId, String messageId, String runId) {
        jdbc.update("UPDATE channel_inbound_messages SET run_id = ? WHERE binding_id = ? AND message_id = ?",
                runId, bindingId, messageId);
    }

    private static ChannelBindingRecord binding(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ChannelBindingRecord(
                rs.getString("id"), rs.getString("user_id"), rs.getString("agent_id"),
                rs.getString("channel_type"), rs.getString("account_id"), rs.getString("display_name"),
                rs.getString("credentials_json"), rs.getBoolean("enabled"), rs.getBoolean("shared_identity"),
                rs.getString("state_json"), rs.getLong("created_at"), rs.getLong("updated_at"));
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
