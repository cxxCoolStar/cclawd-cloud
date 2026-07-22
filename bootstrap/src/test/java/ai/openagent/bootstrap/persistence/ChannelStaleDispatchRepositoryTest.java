package ai.openagent.bootstrap.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.openagent.bootstrap.channel.ChannelMessageStatus;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class ChannelStaleDispatchRepositoryTest {

    @Test
    void listsAndTouchesOnlyStalePublishedNotifications() {
        SingleConnectionDataSource dataSource =
                new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        try {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("CREATE TABLE channel_message_inbox (id TEXT, status TEXT, published_at BIGINT, updated_at BIGINT)");
            jdbc.execute("CREATE TABLE channel_message_outbox (id TEXT, status TEXT, published_at BIGINT, updated_at BIGINT)");
            jdbc.update("INSERT INTO channel_message_inbox VALUES (?, ?, ?, ?)",
                    "stale-in", ChannelMessageStatus.PUBLISHED.name(), 10L, 10L);
            jdbc.update("INSERT INTO channel_message_inbox VALUES (?, ?, ?, ?)",
                    "fresh-in", ChannelMessageStatus.PUBLISHED.name(), 100L, 100L);
            jdbc.update("INSERT INTO channel_message_outbox VALUES (?, ?, ?, ?)",
                    "stale-out", ChannelMessageStatus.PUBLISHED.name(), 10L, 10L);

            ChannelStaleDispatchRepository repository = new ChannelStaleDispatchRepository(jdbc);
            assertEquals(List.of("stale-in"), repository.listStaleInboundIds(50L, 10));
            assertEquals(List.of("stale-out"), repository.listStaleOutboundIds(50L, 10));

            repository.touchInbound("stale-in", 200L);
            repository.touchOutbound("stale-out", 200L);
            assertEquals(List.of(), repository.listStaleInboundIds(50L, 10));
            assertEquals(List.of(), repository.listStaleOutboundIds(50L, 10));
        } finally {
            dataSource.destroy();
        }
    }
}
