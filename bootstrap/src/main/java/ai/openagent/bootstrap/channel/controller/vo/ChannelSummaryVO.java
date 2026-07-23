package ai.openagent.bootstrap.channel.controller.vo;

public record ChannelSummaryVO(
        long accountCount,
        long messagesToday,
        long inboxBacklog,
        long outboxBacklog,
        long interruptedCount,
        long deadCount) {}
