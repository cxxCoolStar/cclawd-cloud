package ai.openagent.bootstrap.channel;

import java.util.function.Consumer;

/** Lifecycle and text transport boundary for one external channel account. */
public interface ImChannelAdapter extends AutoCloseable {

    String accountId();

    void start(Consumer<ChannelInboundMessage> inboundHandler);

    void send(String chatId, String text, String contextToken);

    String status();

    @Override
    void close();
}
