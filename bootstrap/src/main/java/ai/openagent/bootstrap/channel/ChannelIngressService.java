package ai.openagent.bootstrap.channel;

import ai.openagent.bootstrap.persistence.ChannelBindingRecord;
import ai.openagent.bootstrap.persistence.ChannelConversationRecord;
import ai.openagent.bootstrap.persistence.ChannelDispatchRepository;
import ai.openagent.bootstrap.persistence.ChannelInboxRecord;
import ai.openagent.bootstrap.persistence.ChannelMessageRepository;
import ai.openagent.bootstrap.persistence.ChannelRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Persists a channel message before notifying an Agent worker. */
@Service
@RequiredArgsConstructor
public class ChannelIngressService {

    private final ChannelRepository channelRepository;
    private final ChannelMessageRepository messageRepository;
    private final ChannelDispatchRepository dispatchRepository;
    private final ChannelMessageBus messageBus;

    public boolean accept(ChannelBindingRecord binding, ChannelInboundMessage message) {
        ChannelConversationRecord conversation = channelRepository.resolveConversation(
                binding, message.chatId(), message.chatterId(), message.contextToken());
        Optional<ChannelInboxRecord> accepted =
                messageRepository.acceptInbound(binding, conversation, message);
        if (accepted.isEmpty()) {
            return false;
        }
        ChannelInboxRecord inbox = accepted.get();
        messageBus.publishInbound(new ChannelInboundTask(inbox.id()));
        dispatchRepository.markInboundPublished(inbox.id(), System.currentTimeMillis());
        return true;
    }
}
