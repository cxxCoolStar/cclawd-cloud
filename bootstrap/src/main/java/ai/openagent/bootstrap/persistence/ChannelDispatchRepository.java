package ai.openagent.bootstrap.persistence;

import ai.openagent.bootstrap.channel.ChannelMessageStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Records the handoff between durable channel rows and a message bus. */
@Repository
@RequiredArgsConstructor
public class ChannelDispatchRepository {

    private final JdbcTemplate jdbc;

    public void markInboundPublished(String inboxId, long now) {
        jdbc.update("""
                UPDATE channel_message_inbox
                   SET status = ?, published_at = ?, updated_at = ?
                 WHERE id = ? AND status IN (?, ?)
                """,
                ChannelMessageStatus.PUBLISHED.name(), now, now, inboxId,
                ChannelMessageStatus.RECEIVED.name(), ChannelMessageStatus.RETRY_WAIT.name());
    }

    public void deferInbound(String inboxId) {
        jdbc.update("""
                UPDATE channel_message_inbox SET status = ?, updated_at = ?
                 WHERE id = ? AND status = ?
                """,
                ChannelMessageStatus.RECEIVED.name(), System.currentTimeMillis(), inboxId,
                ChannelMessageStatus.PUBLISHED.name());
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

    public void deferOutbound(String outboxId) {
        jdbc.update("""
                UPDATE channel_message_outbox SET status = ?, updated_at = ?
                 WHERE id = ? AND status = ?
                """,
                ChannelMessageStatus.READY.name(), System.currentTimeMillis(), outboxId,
                ChannelMessageStatus.PUBLISHED.name());
    }
}
