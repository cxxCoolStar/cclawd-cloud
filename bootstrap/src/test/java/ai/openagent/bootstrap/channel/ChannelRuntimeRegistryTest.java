package ai.openagent.bootstrap.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class ChannelRuntimeRegistryTest {

    @Test
    void localRegistryPreservesMessageTimestampAcrossHeartbeats() {
        LocalChannelRuntimeRegistry registry = new LocalChannelRuntimeRegistry();

        registry.report("binding-1", "connecting", "");
        registry.recordMessage("binding-1", 123L);
        registry.report("binding-1", "connected", "");

        ChannelRuntimeSnapshot snapshot = registry.find("binding-1").orElseThrow();
        assertEquals(registry.ownerId(), snapshot.ownerId());
        assertEquals("connected", snapshot.adapterStatus());
        assertEquals(123L, snapshot.lastMessageAt());

        registry.remove("binding-1");
        assertTrue(registry.find("binding-1").isEmpty());
    }

    @Test
    void resolvesClusterStatusFromLeaseAndHeartbeat() {
        ChannelRuntimeSnapshot connected =
                new ChannelRuntimeSnapshot("gateway-1", "connected", 1L, null, "");

        assertEquals(ChannelClusterStatus.DISABLED,
                ChannelClusterStatus.resolve(false, true, Optional.of(connected)));
        assertEquals(ChannelClusterStatus.UNAVAILABLE,
                ChannelClusterStatus.resolve(true, false, Optional.of(connected)));
        assertEquals(ChannelClusterStatus.DEGRADED,
                ChannelClusterStatus.resolve(true, true, Optional.empty()));
        assertEquals(ChannelClusterStatus.HEALTHY,
                ChannelClusterStatus.resolve(true, true, Optional.of(connected)));
        assertEquals(ChannelClusterStatus.STARTING,
                ChannelClusterStatus.resolve(true, true,
                        Optional.of(new ChannelRuntimeSnapshot("gateway-1", "connecting", 1L, null, ""))));
        assertEquals(ChannelClusterStatus.ERROR,
                ChannelClusterStatus.resolve(true, true,
                        Optional.of(new ChannelRuntimeSnapshot("gateway-1", "expired", 1L, null, ""))));
        assertTrue(ChannelClusterStatus.isActive(ChannelClusterStatus.DEGRADED));
        assertFalse(ChannelClusterStatus.isActive(ChannelClusterStatus.UNAVAILABLE));
    }
}
