package ai.openagent.bootstrap.channel;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Database-backed channel reliability gauges. */
@Component
public class ChannelMetrics {

    private final JdbcTemplate jdbc;

    public ChannelMetrics(JdbcTemplate jdbc, MeterRegistry meterRegistry) {
        this.jdbc = jdbc;
        gauge(meterRegistry, "openagent.channel.inbox.backlog",
                "Durable inbound messages awaiting completion",
                "channel_message_inbox", "RECEIVED", "PUBLISHED", "RETRY_WAIT", "PROCESSING");
        gauge(meterRegistry, "openagent.channel.outbox.backlog",
                "Durable outbound messages awaiting delivery",
                "channel_message_outbox", "READY", "PUBLISHED", "RETRY_WAIT", "DELIVERING");
        gauge(meterRegistry, "openagent.channel.inbox.interrupted",
                "Inbound messages interrupted after an Agent run started",
                "channel_message_inbox", "INTERRUPTED");
        gauge(meterRegistry, "openagent.channel.inbox.dead",
                "Inbound messages that require manual intervention",
                "channel_message_inbox", "DEAD");
        gauge(meterRegistry, "openagent.channel.outbox.dead",
                "Outbound messages that exhausted delivery retries",
                "channel_message_outbox", "DEAD");
    }

    private void gauge(
            MeterRegistry registry, String name, String description, String table, String... statuses) {
        Gauge.builder(name, () -> count(table, statuses))
                .description(description)
                .register(registry);
    }

    private double count(String table, String... statuses) {
        String placeholders = String.join(",", java.util.Collections.nCopies(statuses.length, "?"));
        try {
            Long count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + table + " WHERE status IN (" + placeholders + ")",
                    Long.class,
                    (Object[]) statuses);
            return count == null ? 0D : count.doubleValue();
        } catch (RuntimeException error) {
            return Double.NaN;
        }
    }
}
