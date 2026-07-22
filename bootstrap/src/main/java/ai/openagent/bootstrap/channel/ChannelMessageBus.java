package ai.openagent.bootstrap.channel;

/** Transport boundary between channel ingress, Agent workers, and channel egress. */
public interface ChannelMessageBus {

    void publishInbound(ChannelInboundTask task);

    ChannelDelivery<ChannelInboundTask> takeInbound() throws InterruptedException;

    void publishOutbound(ChannelOutboundTask task);

    ChannelDelivery<ChannelOutboundTask> takeOutbound() throws InterruptedException;
}
