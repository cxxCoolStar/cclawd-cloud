package ai.openagent.bootstrap.channel;

import java.util.Objects;

/** Normalized text message received from an IM channel. */
public record ChannelInboundMessage(
        String channel,
        String accountId,
        String chatId,
        String chatterId,
        String messageId,
        String text,
        String contextToken) {

    public ChannelInboundMessage {
        channel = requireText(channel, "channel");
        accountId = requireText(accountId, "accountId");
        chatId = requireText(chatId, "chatId");
        chatterId = requireText(chatterId, "chatterId");
        messageId = requireText(messageId, "messageId");
        text = requireText(text, "text");
        contextToken = Objects.requireNonNullElse(contextToken, "");
        if (!channel.matches("[a-z0-9_-]{1,40}")) {
            throw new IllegalArgumentException("invalid channel");
        }
        requireMaxLength(accountId, "accountId", 255);
        requireMaxLength(chatId, "chatId", 512);
        requireMaxLength(chatterId, "chatterId", 512);
        requireMaxLength(messageId, "messageId", 255);
        requireMaxLength(contextToken, "contextToken", 8192);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static void requireMaxLength(String value, String name, int maxLength) {
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(name + " exceeds " + maxLength + " characters");
        }
    }
}
