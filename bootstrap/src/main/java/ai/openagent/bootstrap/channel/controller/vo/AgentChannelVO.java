package ai.openagent.bootstrap.channel.controller.vo;

import ai.openagent.bootstrap.persistence.ChannelBindingRecord;
import java.time.Instant;

public record AgentChannelVO(
        String type,
        String accountId,
        String botUsername,
        String botToken,
        boolean enabled,
        boolean sharedIdentity,
        String status,
        String updatedAt) {

    public static AgentChannelVO from(ChannelBindingRecord binding, String status) {
        return new AgentChannelVO(
                binding.channelType(),
                binding.accountId(),
                binding.displayName(),
                "********",
                binding.enabled(),
                binding.sharedIdentity(),
                status,
                Instant.ofEpochMilli(binding.updatedAt()).toString());
    }
}