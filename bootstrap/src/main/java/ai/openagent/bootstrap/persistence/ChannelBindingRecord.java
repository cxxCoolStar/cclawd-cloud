package ai.openagent.bootstrap.persistence;

public record ChannelBindingRecord(
        String id,
        String userId,
        String agentId,
        String channelType,
        String accountId,
        String displayName,
        String credentialsJson,
        boolean enabled,
        boolean sharedIdentity,
        String stateJson,
        long createdAt,
        long updatedAt) {}
