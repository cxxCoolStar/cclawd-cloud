package ai.openagent.bootstrap.channel;

/** Sends final replies through one external channel account. */
public interface ChannelSender {

    String accountId();

    void send(String chatId, String text, String contextToken);
}
