package ai.openagent.bootstrap.channel;

import java.util.Objects;

/** Reference to a durable channel outbox record. */
public record ChannelOutboundTask(String outboxId) {

    public ChannelOutboundTask {
        Objects.requireNonNull(outboxId, "outboxId");
        if (outboxId.isBlank()) {
            throw new IllegalArgumentException("outboxId must not be blank");
        }
    }
}
