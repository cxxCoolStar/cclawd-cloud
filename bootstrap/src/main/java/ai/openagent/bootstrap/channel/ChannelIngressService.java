package ai.openagent.bootstrap.channel;

import ai.openagent.bootstrap.persistence.ChannelBindingRecord;
import ai.openagent.bootstrap.persistence.ChannelConversationRecord;
import ai.openagent.bootstrap.persistence.ChannelDispatchRepository;
import ai.openagent.bootstrap.persistence.ChannelInboxRecord;
import ai.openagent.bootstrap.persistence.ChannelMessageRepository;
import ai.openagent.bootstrap.persistence.ChannelRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Persists a channel message before notifying an Agent worker. */
@Slf4j
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
            log.info(
                    "[channel-trace] Inbound duplicate ignored, bindingId={}, accountId={}, externalMessageId={}",
                    binding.id(), binding.accountId(), message.messageId());
            return false;
        }
        ChannelInboxRecord inbox = accepted.get();
        log.info(
                "[channel-trace] Inbound persisted, inboxId={}, bindingId={}, conversationId={}, sequenceNo={}, externalMessageId={}, textLength={}",
                inbox.id(), inbox.bindingId(), inbox.conversationId(), inbox.sequenceNo(),
                inbox.externalMessageId(), inbox.text().length());
        messageBus.publishInbound(new ChannelInboundTask(inbox.id()));
        dispatchRepository.markInboundPublished(inbox.id(), System.currentTimeMillis());
        log.info("[channel-trace] Inbound published, inboxId={}", inbox.id());
        return true;
    }
}
