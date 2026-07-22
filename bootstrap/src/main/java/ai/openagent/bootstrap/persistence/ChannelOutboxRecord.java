package ai.openagent.bootstrap.persistence;

import ai.openagent.bootstrap.channel.ChannelMessageStatus;

public record ChannelOutboxRecord(
        String id,
        String inboxId,
        String bindingId,
        String conversationId,
        String runId,
        long sequenceNo,
        String chatId,
        String text,
        String contextToken,
        ChannelMessageStatus status,
        int attempts,
        long availableAt,
        String claimedBy,
        Long claimExpiresAt,
        String providerMessageId,
        String lastError,
        Long publishedAt,
        Long sentAt,
        long createdAt,
        long updatedAt) {}
