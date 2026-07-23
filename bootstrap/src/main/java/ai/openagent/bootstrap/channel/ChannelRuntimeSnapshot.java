package ai.openagent.bootstrap.channel;

/** Cross-process heartbeat for the ingress adapter that owns a binding lease. */
public record ChannelRuntimeSnapshot(
        String ownerId,
        String adapterStatus,
        long heartbeatAt,
        Long lastMessageAt,
        String lastError) {}
