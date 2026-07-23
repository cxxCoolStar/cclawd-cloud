package ai.openagent.bootstrap.channel.controller.vo;

import java.util.List;

public record ChannelRuntimeVO(
        String bus,
        List<String> roles,
        int configuredAccounts,
        int activeAccounts,
        long inboxBacklog,
        long outboxBacklog,
        String status) {}
