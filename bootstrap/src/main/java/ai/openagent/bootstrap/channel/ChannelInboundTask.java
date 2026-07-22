package ai.openagent.bootstrap.channel;

import java.util.Objects;

/** Reference to a durable channel inbox record. */
public record ChannelInboundTask(String inboxId) {

    public ChannelInboundTask {
        Objects.requireNonNull(inboxId, "inboxId");
        if (inboxId.isBlank()) {
            throw new IllegalArgumentException("inboxId must not be blank");
        }
    }
}
