package ai.openagent.bootstrap.channel;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ChannelInboundMessageTest {

    @Test
    void rejectsMalformedAndOversizedExternalIdentifiers() {
        assertThrows(IllegalArgumentException.class,
                () -> message("WeChat", "account", "chat", "user", "id", ""));
        assertThrows(IllegalArgumentException.class,
                () -> message("wechat", "a".repeat(256), "chat", "user", "id", ""));
        assertThrows(IllegalArgumentException.class,
                () -> message("wechat", "account", "c".repeat(513), "user", "id", ""));
        assertThrows(IllegalArgumentException.class,
                () -> message("wechat", "account", "chat", "user", "m".repeat(256), ""));
        assertThrows(IllegalArgumentException.class,
                () -> message("wechat", "account", "chat", "user", "id", "x".repeat(8193)));
    }

    private static ChannelInboundMessage message(
            String channel,
            String accountId,
            String chatId,
            String chatterId,
            String messageId,
            String contextToken) {
        return new ChannelInboundMessage(
                channel, accountId, chatId, chatterId, messageId, "hello", contextToken);
    }
}
