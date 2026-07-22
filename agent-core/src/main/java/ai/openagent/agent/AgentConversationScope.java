package ai.openagent.agent;

import java.util.Objects;

/** External conversation identity carried through one Agent run. */
public record AgentConversationScope(
        String channel,
        String accountId,
        String chatId,
        String chatterId,
        String memoryScopeId,
        boolean sharedIdentity) {

    public AgentConversationScope(
            String channel,
            String accountId,
            String chatId,
            String chatterId,
            String memoryScopeId) {
        this(channel, accountId, chatId, chatterId, memoryScopeId, false);
    }

    public AgentConversationScope {
        channel = requireText(channel, "channel");
        accountId = requireText(accountId, "accountId");
        chatId = requireText(chatId, "chatId");
        chatterId = requireText(chatterId, "chatterId");
        memoryScopeId = requireText(memoryScopeId, "memoryScopeId");
        if (!channel.matches("[a-z0-9_-]{1,40}")) {
            throw new IllegalArgumentException("invalid channel");
        }
        requireMaxLength(accountId, "accountId", 255);
        requireMaxLength(chatId, "chatId", 512);
        requireMaxLength(chatterId, "chatterId", 512);
        if (!memoryScopeId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("invalid memoryScopeId");
        }
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
