package ai.openagent.bootstrap.channel;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** In-process message bus used by the default single-node runtime. */
@Component
@ConditionalOnProperty(name = "openagent.channel.bus", havingValue = "local", matchIfMissing = true)
public class LocalChannelMessageBus implements ChannelMessageBus {

    private final BlockingQueue<ChannelInboundTask> inbound = new LinkedBlockingQueue<>();
    private final BlockingQueue<ChannelOutboundTask> outbound = new LinkedBlockingQueue<>();

    @Override
    public void publishInbound(ChannelInboundTask task) {
        inbound.add(task);
    }

    @Override
    public ChannelDelivery<ChannelInboundTask> takeInbound() throws InterruptedException {
        return new ChannelDelivery<>(inbound.take(), () -> {});
    }

    @Override
    public void publishOutbound(ChannelOutboundTask task) {
        outbound.add(task);
    }

    @Override
    public ChannelDelivery<ChannelOutboundTask> takeOutbound() throws InterruptedException {
        return new ChannelDelivery<>(outbound.take(), () -> {});
    }
}
