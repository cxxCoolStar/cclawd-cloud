package ai.openagent.bootstrap.persistence;

import ai.openagent.bootstrap.channel.ChannelMessageStatus;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Finds notifications that were published but may have been lost from the transport. */
@Repository
@RequiredArgsConstructor
public class ChannelStaleDispatchRepository {

    private final JdbcTemplate jdbc;

    public List<String> listStaleInboundIds(long publishedBefore, int limit) {
        return jdbc.query("""
                SELECT id FROM channel_message_inbox
                 WHERE status = ? AND published_at <= ?
                 ORDER BY published_at LIMIT ?
                """,
                (rs, row) -> rs.getString("id"),
                ChannelMessageStatus.PUBLISHED.name(), publishedBefore, limit);
    }

    public List<String> listStaleOutboundIds(long publishedBefore, int limit) {
        return jdbc.query("""
                SELECT id FROM channel_message_outbox
                 WHERE status = ? AND published_at <= ?
                 ORDER BY published_at LIMIT ?
                """,
                (rs, row) -> rs.getString("id"),
                ChannelMessageStatus.PUBLISHED.name(), publishedBefore, limit);
    }

    public void touchInbound(String inboxId, long now) {
        jdbc.update("""
                UPDATE channel_message_inbox SET published_at = ?, updated_at = ?
                 WHERE id = ? AND status = ?
                """, now, now, inboxId, ChannelMessageStatus.PUBLISHED.name());
    }

    public void touchOutbound(String outboxId, long now) {
        jdbc.update("""
                UPDATE channel_message_outbox SET published_at = ?, updated_at = ?
                 WHERE id = ? AND status = ?
                """, now, now, outboxId, ChannelMessageStatus.PUBLISHED.name());
    }
}
