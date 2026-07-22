package ai.openagent.bootstrap.channel.config;

import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Configuration for local and distributed channel processing. */
@ConfigurationProperties(prefix = "openagent.channel")
public record ChannelProperties(
        @DefaultValue("local") String bus,
        @DefaultValue("api,channel-ingress,agent-worker,channel-egress") Set<String> roles,
        @DefaultValue Redis redis,
        @DefaultValue Lease lease) {

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public record Redis(
            @DefaultValue("openagent:channel") String keyPrefix,
            @DefaultValue("2s") Duration blockTimeout,
            @DefaultValue("30s") Duration pendingIdle,
            @DefaultValue("20") int reclaimBatchSize) {}

    public record Lease(
            @DefaultValue("30s") Duration ttl,
            @DefaultValue("10s") Duration renewInterval) {}
}
