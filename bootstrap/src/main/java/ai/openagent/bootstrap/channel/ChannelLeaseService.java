package ai.openagent.bootstrap.channel;

/** Coordinates the single active receiver for each channel binding. */
public interface ChannelLeaseService {

    boolean acquire(String bindingId);

    boolean renew(String bindingId);

    void release(String bindingId);
}
