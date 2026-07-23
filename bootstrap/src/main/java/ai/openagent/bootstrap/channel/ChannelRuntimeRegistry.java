package ai.openagent.bootstrap.channel;

import java.util.Optional;

/** Shared runtime heartbeat registry for Channel ingress owners. */
public interface ChannelRuntimeRegistry {

    String ownerId();

    void report(String bindingId, String adapterStatus, String lastError);

    void recordMessage(String bindingId, long receivedAt);

    Optional<ChannelRuntimeSnapshot> find(String bindingId);

    void remove(String bindingId);
}
