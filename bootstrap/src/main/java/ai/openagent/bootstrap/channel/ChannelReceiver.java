package ai.openagent.bootstrap.channel;

import java.util.function.Consumer;

/** Receives normalized messages for one external channel account. */
public interface ChannelReceiver extends AutoCloseable {

    String accountId();

    void start(Consumer<ChannelInboundMessage> inboundHandler);

    String status();

    @Override
    void close();
}
