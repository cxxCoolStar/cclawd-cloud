package ai.openagent.bootstrap.channel;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "openagent.channel.bus", havingValue = "local", matchIfMissing = true)
public class LocalChannelRuntimeRegistry implements ChannelRuntimeRegistry {

    private final String ownerId = "local-" + UUID.randomUUID();
    private final Map<String, ChannelRuntimeSnapshot> snapshots = new ConcurrentHashMap<>();

    @Override
    public String ownerId() {
        return ownerId;
    }

    @Override
    public void report(String bindingId, String adapterStatus, String lastError) {
        snapshots.compute(bindingId, (key, current) -> new ChannelRuntimeSnapshot(
                ownerId,
                adapterStatus,
                System.currentTimeMillis(),
                current == null ? null : current.lastMessageAt(),
                value(lastError)));
    }

    @Override
    public void recordMessage(String bindingId, long receivedAt) {
        snapshots.computeIfPresent(bindingId, (key, current) -> new ChannelRuntimeSnapshot(
                current.ownerId(), current.adapterStatus(), current.heartbeatAt(), receivedAt, current.lastError()));
    }

    @Override
    public Optional<ChannelRuntimeSnapshot> find(String bindingId) {
        return Optional.ofNullable(snapshots.get(bindingId));
    }

    @Override
    public void remove(String bindingId) {
        snapshots.computeIfPresent(bindingId, (key, current) ->
                ownerId.equals(current.ownerId()) ? null : current);
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
