package ai.openagent.bootstrap.channel.controller.vo;

public record ChannelMessageVO(
        String id,
        String type,
        String accountId,
        String displayName,
        String senderId,
        String text,
        String inboxStatus,
        String outboxStatus,
        String runId,
        int attempts,
        String lastError,
        String createdAt,
        String updatedAt,
        String sentAt) {}
