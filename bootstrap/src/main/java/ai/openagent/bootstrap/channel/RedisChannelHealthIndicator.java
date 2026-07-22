package ai.openagent.bootstrap.channel;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Readiness dependency for the distributed channel transport. */
@Component("channelRedisHealthIndicator")
@ConditionalOnProperty(name = "openagent.channel.bus", havingValue = "redis")
public class RedisChannelHealthIndicator implements HealthIndicator {

    private final StringRedisTemplate redis;

    public RedisChannelHealthIndicator(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Health health() {
        try (RedisConnection connection = redis.getConnectionFactory().getConnection()) {
            String pong = connection.ping();
            return "PONG".equalsIgnoreCase(pong)
                    ? Health.up().build()
                    : Health.down().withDetail("response", pong).build();
        } catch (RuntimeException error) {
            return Health.down(error).build();
        }
    }
}
