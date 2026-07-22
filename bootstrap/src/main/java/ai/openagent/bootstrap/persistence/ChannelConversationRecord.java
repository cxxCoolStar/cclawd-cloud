package ai.openagent.bootstrap.persistence;

import ai.openagent.agent.AgentConversationScope;

public record ChannelConversationRecord(
        String id,
        String bindingId,
        String chatId,
        String chatterId,
        String sessionId,
        String contextToken,
        long createdAt,
        long updatedAt) {

    public AgentConversationScope toScope(ChannelBindingRecord binding) {
        return new AgentConversationScope(
                binding.channelType(), binding.accountId(), chatId, chatterId, id, binding.sharedIdentity());
    }
}
