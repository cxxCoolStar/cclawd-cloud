package ai.openagent.bootstrap.channel.controller.vo;

public record ChannelAccountVO(
        String id,
        String agentId,
        String type,
        String accountId,
        String displayName,
        boolean enabled,
        boolean sharedIdentity,
        String clusterStatus,
        String adapterStatus,
        String ownerId,
        String lastHeartbeatAt,
        String lastMessageAt,
        String lastError,
        boolean leaseActive,
        long inboxBacklog,
        long outboxBacklog,
        String updatedAt) {}
