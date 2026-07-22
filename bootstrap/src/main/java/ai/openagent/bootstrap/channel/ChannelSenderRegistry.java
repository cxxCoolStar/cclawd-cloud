package ai.openagent.bootstrap.channel;

import java.util.Optional;

/** Resolves the sender registered for a channel account. */
public interface ChannelSenderRegistry {

    Optional<ChannelSender> findSender(String channel, String accountId);
}
