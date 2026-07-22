package ai.openagent.bootstrap.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.openagent.bootstrap.OpenAgentApplication;
import ai.openagent.bootstrap.identity.IdentityConstant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        classes = OpenAgentApplication.class,
        properties = {
            "spring.datasource.url=jdbc:sqlite:target/channel-repository-test.db",
            "openagent.model.api-key=test-key",
            "openagent.model.name=test-model"
        })
class ChannelRepositoryTest {

    @Autowired
    private ChannelRepository repository;

    @Test
    void conversationsAndInboundMessagesAreIsolatedByBinding() {
        ChannelBindingRecord first = binding("account-" + UUID.randomUUID());
        ChannelBindingRecord second = binding("account-" + UUID.randomUUID());
        repository.insertBinding(first);
        repository.insertBinding(second);

        ChannelConversationRecord firstConversation =
                repository.resolveConversation(first, "same-chat", "chatter-a", "ctx-a");
        ChannelConversationRecord secondConversation =
                repository.resolveConversation(second, "same-chat", "chatter-b", "ctx-b");

        assertNotEquals(firstConversation.sessionId(), secondConversation.sessionId());
        assertNotEquals(firstConversation.id(), secondConversation.id());
        assertEquals(firstConversation.id(),
                repository.resolveConversation(first, "same-chat", "chatter-a", "ctx-new").id());

        assertTrue(repository.claimInbound(first.id(), "message-1", firstConversation.id()));
        assertFalse(repository.claimInbound(first.id(), "message-1", firstConversation.id()));
        assertTrue(repository.claimInbound(second.id(), "message-1", secondConversation.id()));
    }

    private static ChannelBindingRecord binding(String accountId) {
        long now = System.currentTimeMillis();
        return new ChannelBindingRecord(
                UUID.randomUUID().toString(),
                IdentityConstant.LOCAL_USER_ID,
                "default",
                "wechat",
                accountId,
                accountId,
                "{}",
                false,
                false,
                "{}",
                now,
                now);
    }
}
