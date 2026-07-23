package ai.openagent.bootstrap.channel;

import java.util.Locale;
import java.util.Optional;

/** Resolves the user-facing Channel status from shared cluster state. */
public final class ChannelClusterStatus {

    public static final String DISABLED = "DISABLED";
    public static final String UNAVAILABLE = "UNAVAILABLE";
    public static final String DEGRADED = "DEGRADED";
    public static final String HEALTHY = "HEALTHY";
    public static final String STARTING = "STARTING";
    public static final String ERROR = "ERROR";

    private ChannelClusterStatus() {}

    public static String resolve(
            boolean enabled, boolean leaseActive, Optional<ChannelRuntimeSnapshot> runtime) {
        if (!enabled) {
            return DISABLED;
        }
        if (!leaseActive) {
            return UNAVAILABLE;
        }
        if (runtime.isEmpty()) {
            return DEGRADED;
        }
        return switch (runtime.orElseThrow().adapterStatus().toLowerCase(Locale.ROOT)) {
            case "connected" -> HEALTHY;
            case "connecting" -> STARTING;
            case "degraded" -> DEGRADED;
            case "expired", "error", "stopped" -> ERROR;
            default -> DEGRADED;
        };
    }

    public static boolean isActive(String status) {
        return HEALTHY.equals(status) || STARTING.equals(status) || DEGRADED.equals(status);
    }
}
