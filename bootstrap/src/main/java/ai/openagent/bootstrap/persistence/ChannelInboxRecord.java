package ai.openagent.bootstrap.persistence;

import ai.openagent.bootstrap.channel.ChannelMessageStatus;

public record ChannelInboxRecord(
        String id,
        String bindingId,
        String conversationId,
        String externalMessageId,
        long sequenceNo,
        String text,
        String contextToken,
        ChannelMessageStatus status,
        int attempts,
        long availableAt,
        String claimedBy,
        Long claimExpiresAt,
        String runId,
        String lastError,
        Long publishedAt,
        Long completedAt,
        long createdAt,
        long updatedAt) {}
