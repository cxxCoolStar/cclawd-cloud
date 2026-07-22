package ai.openagent.bootstrap.persistence;

import ai.openagent.bootstrap.channel.ChannelInboundMessage;

public record ChannelInboxWorkItem(
        ChannelInboxRecord inbox,
        ChannelBindingRecord binding,
        ChannelConversationRecord conversation,
        ChannelInboundMessage message) {}
