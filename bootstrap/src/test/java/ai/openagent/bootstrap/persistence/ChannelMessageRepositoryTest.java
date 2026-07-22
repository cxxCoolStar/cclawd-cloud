package ai.openagent.bootstrap.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.openagent.bootstrap.OpenAgentApplication;
import ai.openagent.bootstrap.channel.ChannelInboundMessage;
import ai.openagent.bootstrap.channel.ChannelMessageStatus;
import ai.openagent.bootstrap.identity.IdentityConstant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        classes = OpenAgentApplication.class,
        properties = {
            "openagent.model.api-key=test-key",
            "openagent.model.name=test-model"
        })
class ChannelMessageRepositoryTest {

    private static final String DATABASE_ID = UUID.randomUUID().toString();

    @Autowired
    private ChannelRepository channelRepository;

    @Autowired
    private ChannelMessageRepository messageRepository;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:target/channel-message-repository-" + DATABASE_ID + ".db");
    }

    @Test
    void inboxIsIdempotentAndClaimsOneConversationInSequence() {
        ChannelBindingRecord binding = binding();
        channelRepository.insertBinding(binding);
        ChannelConversationRecord conversation = channelRepository.resolveConversation(
                binding, "chat-1", "chatter-1", "ctx-1");

        ChannelInboxRecord first = messageRepository.acceptInbound(
                        binding, conversation, message(binding, "message-1", "first"))
                .orElseThrow();
        ChannelInboxRecord second = messageRepository.acceptInbound(
                        binding, conversation, message(binding, "message-2", "second"))
                .orElseThrow();

        assertEquals(1, first.sequenceNo());
        assertEquals(2, second.sequenceNo());
        assertTrue(messageRepository.acceptInbound(
                binding, conversation, message(binding, "message-1", "duplicate")).isEmpty());

        long now = System.currentTimeMillis();
        assertTrue(messageRepository.claimInbound(second.id(), "worker-b", now + 60_000, now).isEmpty());
        assertTrue(messageRepository.claimInbound(first.id(), "worker-a", now + 60_000, now).isPresent());
        messageRepository.attachRun(first.id(), "run-1", now + 60_000);
        assertTrue(messageRepository.completeInbound(
                first, "run-1", "chat-1", "", "ctx-1").isEmpty());

        assertTrue(messageRepository.claimInbound(
                second.id(), "worker-b", now + 60_000, now).isPresent());
        messageRepository.attachRun(second.id(), "run-2", now + 60_000);
        ChannelOutboxRecord outbox = messageRepository.completeInbound(
                        second, "run-2", "chat-1", "reply", "ctx-2")
                .orElseThrow();

        assertEquals(ChannelMessageStatus.READY, outbox.status());
        long deliveryNow = System.currentTimeMillis();
        Optional<ChannelOutboxRecord> claimed = messageRepository.claimOutbound(
                outbox.id(), "gateway-a", deliveryNow + 60_000, deliveryNow);
        assertTrue(claimed.isPresent());
        messageRepository.markOutboundSent(outbox.id(), "provider-1");
        assertEquals(ChannelMessageStatus.SENT,
                messageRepository.findOutbox(outbox.id()).orElseThrow().status());
    }

    @Test
    void expiredPreRunClaimReturnsToRetryQueue() {
        ChannelBindingRecord binding = binding();
        channelRepository.insertBinding(binding);
        ChannelConversationRecord conversation = channelRepository.resolveConversation(
                binding, "chat-2", "chatter-2", "ctx");
        ChannelInboxRecord inbox = messageRepository.acceptInbound(
                        binding, conversation, message(binding, "message-expired", "hello"))
                .orElseThrow();

        long now = System.currentTimeMillis();
        assertFalse(messageRepository.claimInbound(
                inbox.id(), "worker-a", now - 1, now - 2).isEmpty());
        messageRepository.recoverExpiredInbound(inbox.id(), now);

        ChannelInboxRecord recovered = messageRepository.findInbox(inbox.id()).orElseThrow();
        assertEquals(ChannelMessageStatus.RETRY_WAIT, recovered.status());
        assertTrue(recovered.claimedBy() == null);
    }

    private static ChannelBindingRecord binding() {
        long now = System.currentTimeMillis();
        String accountId = "account-" + UUID.randomUUID();
        return new ChannelBindingRecord(
                UUID.randomUUID().toString(), IdentityConstant.LOCAL_USER_ID, "default",
                "wechat", accountId, accountId, "{}", false, false, "{}", now, now);
    }

    private static ChannelInboundMessage message(
            ChannelBindingRecord binding, String messageId, String text) {
        return new ChannelInboundMessage(
                binding.channelType(), binding.accountId(), "chat-1", "chatter-1",
                messageId, text, "ctx");
    }
}
