package ai.openagent.bootstrap.persistence;

import ai.openagent.bootstrap.channel.ChannelInboundMessage;
import ai.openagent.bootstrap.channel.ChannelMessageStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Durable inbox/outbox state and cross-process head-of-line claims. */
@Repository
@RequiredArgsConstructor
public class ChannelMessageRepository {

    private final JdbcTemplate jdbc;

    @Transactional
    public Optional<ChannelInboxRecord> acceptInbound(
            ChannelBindingRecord binding,
            ChannelConversationRecord conversation,
            ChannelInboundMessage message) {
        Optional<ChannelInboxRecord> duplicate = findInbox(binding.id(), message.messageId());
        if (duplicate.isPresent()) {
            return Optional.empty();
        }
        jdbc.update(
                "UPDATE channel_conversations SET next_sequence = next_sequence + 1, updated_at = ? WHERE id = ?",
                System.currentTimeMillis(), conversation.id());
        Long sequence = jdbc.queryForObject(
                "SELECT next_sequence FROM channel_conversations WHERE id = ?", Long.class, conversation.id());
        long now = System.currentTimeMillis();
        ChannelInboxRecord record = new ChannelInboxRecord(
                UUID.randomUUID().toString(),
                binding.id(),
                conversation.id(),
                message.messageId(),
                sequence == null ? 1 : sequence,
                message.text(),
                message.contextToken(),
                ChannelMessageStatus.RECEIVED,
                0,
                now,
                null,
                null,
                null,
                "",
                null,
                null,
                now,
                now);
        try {
            jdbc.update("""
                    INSERT INTO channel_message_inbox
                        (id, binding_id, conversation_id, external_message_id, sequence_no,
                         text, context_token, status, attempts, available_at, claimed_by,
                         claim_expires_at, run_id, last_error, published_at, completed_at,
                         created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, '', NULL, NULL, ?, ?)
                    """,
                    record.id(), record.bindingId(), record.conversationId(), record.externalMessageId(),
                    record.sequenceNo(), record.text(), record.contextToken(), record.status().name(),
                    record.attempts(), record.availableAt(), record.createdAt(), record.updatedAt());
            return Optional.of(record);
        } catch (DataAccessException race) {
            if (findInbox(binding.id(), message.messageId()).isPresent()) {
                return Optional.empty();
            }
            throw race;
        }
    }

    public Optional<ChannelInboxRecord> findInbox(String id) {
        return jdbc.query("SELECT * FROM channel_message_inbox WHERE id = ?", this::inbox, id)
                .stream().findFirst();
    }

    public Optional<ChannelInboxRecord> findInbox(String bindingId, String externalMessageId) {
        return jdbc.query(
                        "SELECT * FROM channel_message_inbox WHERE binding_id = ? AND external_message_id = ?",
                        this::inbox,
                        bindingId,
                        externalMessageId)
                .stream().findFirst();
    }

    @Transactional
    public Optional<ChannelInboxWorkItem> claimInbound(
            String inboxId, String consumerId, long claimExpiresAt, long now) {
        int updated = jdbc.update("""
                UPDATE channel_message_inbox
                   SET status = ?, claimed_by = ?, claim_expires_at = ?, attempts = attempts + 1,
                       updated_at = ?
                 WHERE id = ?
                   AND status IN (?, ?, ?)
                   AND available_at <= ?
                   AND NOT EXISTS (
                       SELECT 1 FROM channel_message_inbox earlier
                        WHERE earlier.conversation_id = channel_message_inbox.conversation_id
                          AND earlier.sequence_no < channel_message_inbox.sequence_no
                          AND earlier.status NOT IN (?, ?, ?, ?)
                   )
                """,
                ChannelMessageStatus.PROCESSING.name(), consumerId, claimExpiresAt, now, inboxId,
                ChannelMessageStatus.RECEIVED.name(), ChannelMessageStatus.PUBLISHED.name(),
                ChannelMessageStatus.RETRY_WAIT.name(), now,
                ChannelMessageStatus.COMPLETED.name(), ChannelMessageStatus.INTERRUPTED.name(),
                ChannelMessageStatus.DEAD.name(), ChannelMessageStatus.SENT.name());
        if (updated == 0) {
            return Optional.empty();
        }
        return findWorkItem(inboxId);
    }

    public Optional<ChannelInboxWorkItem> findWorkItem(String inboxId) {
        return jdbc.query("""
                        SELECT i.*,
                               b.user_id AS b_user_id, b.agent_id AS b_agent_id,
                               b.channel_type AS b_channel_type, b.account_id AS b_account_id,
                               b.display_name AS b_display_name, b.credentials_json AS b_credentials_json,
                               b.enabled AS b_enabled, b.shared_identity AS b_shared_identity,
                               b.state_json AS b_state_json, b.created_at AS b_created_at,
                               b.updated_at AS b_updated_at,
                               c.chat_id AS c_chat_id, c.chatter_id AS c_chatter_id,
                               c.session_id AS c_session_id, c.context_token AS c_context_token,
                               c.created_at AS c_created_at, c.updated_at AS c_updated_at
                          FROM channel_message_inbox i
                          JOIN channel_bindings b ON b.id = i.binding_id
                          JOIN channel_conversations c ON c.id = i.conversation_id
                         WHERE i.id = ?
                        """,
                        (rs, row) -> workItem(rs), inboxId)
                .stream().findFirst();
    }

    public void markInboundPublished(String inboxId, long now) {
        jdbc.update("""
                UPDATE channel_message_inbox
                   SET status = ?, published_at = ?, updated_at = ?
                 WHERE id = ? AND status IN (?, ?)
                """,
                ChannelMessageStatus.PUBLISHED.name(), now, now, inboxId,
                ChannelMessageStatus.RECEIVED.name(), ChannelMessageStatus.RETRY_WAIT.name());
    }

    public void attachRun(String inboxId, String runId, long claimExpiresAt) {
        jdbc.update("""
                UPDATE channel_message_inbox
                   SET run_id = ?, claim_expires_at = ?, updated_at = ?
                 WHERE id = ? AND status = ?
                """,
                runId, claimExpiresAt, System.currentTimeMillis(), inboxId,
                ChannelMessageStatus.PROCESSING.name());
    }

    @Transactional
    public Optional<ChannelOutboxRecord> completeInbound(
            ChannelInboxRecord inbox, String runId, String chatId, String text, String contextToken) {
        long now = System.currentTimeMillis();
        jdbc.update("""
                UPDATE channel_message_inbox
                   SET status = ?, completed_at = ?, claimed_by = NULL, claim_expires_at = NULL,
                       updated_at = ?
                 WHERE id = ? AND status = ?
                """,
                ChannelMessageStatus.COMPLETED.name(), now, now, inbox.id(),
                ChannelMessageStatus.PROCESSING.name());
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        ChannelOutboxRecord outbox = new ChannelOutboxRecord(
                UUID.randomUUID().toString(), inbox.id(), inbox.bindingId(), inbox.conversationId(),
                runId, inbox.sequenceNo(), chatId, text, contextToken == null ? "" : contextToken,
                ChannelMessageStatus.READY, 0, now, null, null, "", "", null, null, now, now);
        jdbc.update("""
                INSERT INTO channel_message_outbox
                    (id, inbox_id, binding_id, conversation_id, run_id, sequence_no, chat_id,
                     text, context_token, status, attempts, available_at, claimed_by,
                     claim_expires_at, provider_message_id, last_error, published_at, sent_at,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, '', '', NULL, NULL, ?, ?)
                """,
                outbox.id(), outbox.inboxId(), outbox.bindingId(), outbox.conversationId(),
                outbox.runId(), outbox.sequenceNo(), outbox.chatId(), outbox.text(), outbox.contextToken(),
                outbox.status().name(), outbox.attempts(), outbox.availableAt(), outbox.createdAt(),
                outbox.updatedAt());
        return Optional.of(outbox);
    }

    public void interruptInbound(String inboxId, String error) {
        long now = System.currentTimeMillis();
        jdbc.update("""
                UPDATE channel_message_inbox
                   SET status = ?, last_error = ?, claimed_by = NULL, claim_expires_at = NULL,
                       completed_at = ?, updated_at = ?
                 WHERE id = ? AND status = ?
                """,
                ChannelMessageStatus.INTERRUPTED.name(), value(error), now, now, inboxId,
                ChannelMessageStatus.PROCESSING.name());
    }

    public void retryInbound(String inboxId, String error, long availableAt) {
        jdbc.update("""
                UPDATE channel_message_inbox
                   SET status = ?, last_error = ?, available_at = ?, claimed_by = NULL,
                       claim_expires_at = NULL, updated_at = ?
                 WHERE id = ? AND status = ?
                """,
                ChannelMessageStatus.RETRY_WAIT.name(), value(error), availableAt,
                System.currentTimeMillis(), inboxId, ChannelMessageStatus.PROCESSING.name());
    }

    public List<String> listPublishableInboundIds(long now, int limit) {
        return jdbc.query("""
                SELECT id FROM channel_message_inbox
                 WHERE status IN (?, ?) AND available_at <= ?
                 ORDER BY created_at LIMIT ?
                """,
                (rs, row) -> rs.getString("id"),
                ChannelMessageStatus.RECEIVED.name(), ChannelMessageStatus.RETRY_WAIT.name(), now, limit);
    }

    public List<String> listExpiredInboundClaimIds(long now, int limit) {
        return jdbc.query("""
                SELECT id FROM channel_message_inbox
                 WHERE status = ? AND claim_expires_at < ?
                 ORDER BY claim_expires_at LIMIT ?
                """,
                (rs, row) -> rs.getString("id"), ChannelMessageStatus.PROCESSING.name(), now, limit);
    }

    public void recoverExpiredInbound(String inboxId, long now) {
        jdbc.update("""
                UPDATE channel_message_inbox
                   SET status = CASE WHEN run_id IS NULL THEN ? ELSE ? END,
                       available_at = ?, claimed_by = NULL, claim_expires_at = NULL,
                       last_error = ?, completed_at = CASE WHEN run_id IS NULL THEN completed_at ELSE ? END,
                       updated_at = ?
                 WHERE id = ? AND status = ?
                """,
                ChannelMessageStatus.RETRY_WAIT.name(), ChannelMessageStatus.INTERRUPTED.name(), now,
                "worker claim expired", now, now, inboxId, ChannelMessageStatus.PROCESSING.name());
    }

    public Optional<String> findNextInboundId(String conversationId) {
        return jdbc.query("""
                        SELECT id FROM channel_message_inbox
                         WHERE conversation_id = ? AND status IN (?, ?)
                         ORDER BY sequence_no LIMIT 1
                        """,
                        (rs, row) -> rs.getString("id"), conversationId,
                        ChannelMessageStatus.RECEIVED.name(), ChannelMessageStatus.RETRY_WAIT.name())
                .stream().findFirst();
    }

    public Optional<ChannelOutboxRecord> findOutbox(String id) {
        return jdbc.query("SELECT * FROM channel_message_outbox WHERE id = ?", this::outbox, id)
                .stream().findFirst();
    }

    @Transactional
    public Optional<ChannelOutboxRecord> claimOutbound(
            String outboxId, String consumerId, long claimExpiresAt, long now) {
        int updated = jdbc.update("""
                UPDATE channel_message_outbox
                   SET status = ?, claimed_by = ?, claim_expires_at = ?, attempts = attempts + 1,
                       updated_at = ?
                 WHERE id = ?
                   AND status IN (?, ?, ?)
                   AND available_at <= ?
                   AND NOT EXISTS (
                       SELECT 1 FROM channel_message_outbox earlier
                        WHERE earlier.conversation_id = channel_message_outbox.conversation_id
                          AND earlier.sequence_no < channel_message_outbox.sequence_no
                          AND earlier.status NOT IN (?, ?)
                   )
                """,
                ChannelMessageStatus.DELIVERING.name(), consumerId, claimExpiresAt, now, outboxId,
                ChannelMessageStatus.READY.name(), ChannelMessageStatus.PUBLISHED.name(),
                ChannelMessageStatus.RETRY_WAIT.name(), now,
                ChannelMessageStatus.SENT.name(), ChannelMessageStatus.DEAD.name());
        if (updated == 0) {
            return Optional.empty();
        }
        return findOutbox(outboxId);
    }

    public void markOutboundPublished(String outboxId, long now) {
        jdbc.update("""
                UPDATE channel_message_outbox
                   SET status = ?, published_at = ?, updated_at = ?
                 WHERE id = ? AND status IN (?, ?)
                """,
                ChannelMessageStatus.PUBLISHED.name(), now, now, outboxId,
                ChannelMessageStatus.READY.name(), ChannelMessageStatus.RETRY_WAIT.name());
    }

    public void markOutboundSent(String outboxId, String providerMessageId) {
        long now = System.currentTimeMillis();
        jdbc.update("""
                UPDATE channel_message_outbox
                   SET status = ?, provider_message_id = ?, sent_at = ?, claimed_by = NULL,
                       claim_expires_at = NULL, updated_at = ?
                 WHERE id = ? AND status = ?
                """,
                ChannelMessageStatus.SENT.name(), value(providerMessageId), now, now, outboxId,
                ChannelMessageStatus.DELIVERING.name());
    }

    public void retryOutbound(String outboxId, String error, long availableAt, boolean dead) {
        jdbc.update("""
                UPDATE channel_message_outbox
                   SET status = ?, last_error = ?, available_at = ?, claimed_by = NULL,
                       claim_expires_at = NULL, updated_at = ?
                 WHERE id = ? AND status = ?
                """,
                (dead ? ChannelMessageStatus.DEAD : ChannelMessageStatus.RETRY_WAIT).name(),
                value(error), availableAt, System.currentTimeMillis(), outboxId,
                ChannelMessageStatus.DELIVERING.name());
    }

    public List<String> listPublishableOutboundIds(long now, int limit) {
        return jdbc.query("""
                SELECT id FROM channel_message_outbox
                 WHERE status IN (?, ?) AND available_at <= ?
                 ORDER BY created_at LIMIT ?
                """,
                (rs, row) -> rs.getString("id"),
                ChannelMessageStatus.READY.name(), ChannelMessageStatus.RETRY_WAIT.name(), now, limit);
    }

    public List<String> listExpiredOutboundClaimIds(long now, int limit) {
        return jdbc.query("""
                SELECT id FROM channel_message_outbox
                 WHERE status = ? AND claim_expires_at < ?
                 ORDER BY claim_expires_at LIMIT ?
                """,
                (rs, row) -> rs.getString("id"), ChannelMessageStatus.DELIVERING.name(), now, limit);
    }

    public void recoverExpiredOutbound(String outboxId, long now) {
        jdbc.update("""
                UPDATE channel_message_outbox
                   SET status = ?, available_at = ?, claimed_by = NULL, claim_expires_at = NULL,
                       last_error = ?, updated_at = ?
                 WHERE id = ? AND status = ?
                """,
                ChannelMessageStatus.RETRY_WAIT.name(), now, "delivery claim expired", now,
                outboxId, ChannelMessageStatus.DELIVERING.name());
    }

    private ChannelInboxWorkItem workItem(ResultSet rs) throws SQLException {
        ChannelInboxRecord inbox = inbox(rs, 0);
        ChannelBindingRecord binding = new ChannelBindingRecord(
                inbox.bindingId(), rs.getString("b_user_id"), rs.getString("b_agent_id"),
                rs.getString("b_channel_type"), rs.getString("b_account_id"),
                rs.getString("b_display_name"), rs.getString("b_credentials_json"),
                rs.getBoolean("b_enabled"), rs.getBoolean("b_shared_identity"),
                rs.getString("b_state_json"), rs.getLong("b_created_at"), rs.getLong("b_updated_at"));
        ChannelConversationRecord conversation = new ChannelConversationRecord(
                inbox.conversationId(), inbox.bindingId(), rs.getString("c_chat_id"),
                rs.getString("c_chatter_id"), rs.getString("c_session_id"),
                rs.getString("c_context_token"), rs.getLong("c_created_at"), rs.getLong("c_updated_at"));
        ChannelInboundMessage message = new ChannelInboundMessage(
                binding.channelType(), binding.accountId(), conversation.chatId(), conversation.chatterId(),
                inbox.externalMessageId(), inbox.text(), inbox.contextToken());
        return new ChannelInboxWorkItem(inbox, binding, conversation, message);
    }

    private ChannelInboxRecord inbox(ResultSet rs, int row) throws SQLException {
        return new ChannelInboxRecord(
                rs.getString("id"), rs.getString("binding_id"), rs.getString("conversation_id"),
                rs.getString("external_message_id"), rs.getLong("sequence_no"), rs.getString("text"),
                rs.getString("context_token"), ChannelMessageStatus.valueOf(rs.getString("status")),
                rs.getInt("attempts"), rs.getLong("available_at"), rs.getString("claimed_by"),
                nullableLong(rs, "claim_expires_at"), rs.getString("run_id"), rs.getString("last_error"),
                nullableLong(rs, "published_at"), nullableLong(rs, "completed_at"),
                rs.getLong("created_at"), rs.getLong("updated_at"));
    }

    private ChannelOutboxRecord outbox(ResultSet rs, int row) throws SQLException {
        return new ChannelOutboxRecord(
                rs.getString("id"), rs.getString("inbox_id"), rs.getString("binding_id"),
                rs.getString("conversation_id"), rs.getString("run_id"), rs.getLong("sequence_no"),
                rs.getString("chat_id"), rs.getString("text"), rs.getString("context_token"),
                ChannelMessageStatus.valueOf(rs.getString("status")), rs.getInt("attempts"),
                rs.getLong("available_at"), rs.getString("claimed_by"),
                nullableLong(rs, "claim_expires_at"), rs.getString("provider_message_id"),
                rs.getString("last_error"), nullableLong(rs, "published_at"),
                nullableLong(rs, "sent_at"), rs.getLong("created_at"), rs.getLong("updated_at"));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
