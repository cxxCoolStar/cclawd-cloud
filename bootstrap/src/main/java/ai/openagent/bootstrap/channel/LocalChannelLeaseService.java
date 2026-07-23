package ai.openagent.bootstrap.channel;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** No-op lease used by the single-node local runtime. */
@Component
@ConditionalOnProperty(name = "openagent.channel.bus", havingValue = "local", matchIfMissing = true)
public class LocalChannelLeaseService implements ChannelLeaseService {

    private final Set<String> activeBindings = ConcurrentHashMap.newKeySet();

    @Override
    public boolean acquire(String bindingId) {
        return true;
    }

    @Override
    public boolean renew(String bindingId) {
        return true;
    }

    @Override
    public void release(String bindingId) {
        activeBindings.remove(bindingId);
    }

    @Override
    public boolean isActive(String bindingId) {
        return activeBindings.contains(bindingId);
    }
}
