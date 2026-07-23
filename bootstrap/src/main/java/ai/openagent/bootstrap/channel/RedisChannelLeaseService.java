package ai.openagent.bootstrap.channel;

import ai.openagent.bootstrap.channel.config.ChannelProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Binding lease using SET NX PX and token-checked Lua renewal/release. */
@Component
@ConditionalOnProperty(name = "openagent.channel.bus", havingValue = "redis")
public class RedisChannelLeaseService implements ChannelLeaseService {

    private static final DefaultRedisScript<Long> RENEW = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
              return redis.call('pexpire', KEYS[1], ARGV[2])
            end
            return 0
            """, Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
              return redis.call('del', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ChannelProperties properties;
    private final String ownerToken = UUID.randomUUID().toString();
    private final Set<String> heldBindings = ConcurrentHashMap.newKeySet();
    private final Counter acquiredCounter;
    private final Counter lostCounter;

    public RedisChannelLeaseService(
            StringRedisTemplate redis, ChannelProperties properties, MeterRegistry meterRegistry) {
        this.redis = redis;
        this.properties = properties;
        this.acquiredCounter = Counter.builder("openagent.channel.leases.acquired")
                .description("Channel binding leases acquired by this process")
                .register(meterRegistry);
        this.lostCounter = Counter.builder("openagent.channel.leases.lost")
                .description("Channel binding leases lost by this process")
                .register(meterRegistry);
        Gauge.builder("openagent.channel.leases.held", heldBindings, Set::size)
                .description("Channel binding leases currently held by this process")
                .register(meterRegistry);
    }

    @Override
    public boolean acquire(String bindingId) {
        Boolean acquired = redis.opsForValue().setIfAbsent(
                key(bindingId), ownerToken, properties.lease().ttl());
        if (Boolean.TRUE.equals(acquired) && heldBindings.add(bindingId)) {
            acquiredCounter.increment();
        }
        return Boolean.TRUE.equals(acquired);
    }

    @Override
    public boolean renew(String bindingId) {
        Long renewed = redis.execute(RENEW, Collections.singletonList(key(bindingId)),
                ownerToken, Long.toString(properties.lease().ttl().toMillis()));
        boolean active = renewed != null && renewed > 0;
        if (!active && heldBindings.remove(bindingId)) {
            lostCounter.increment();
        }
        return active;
    }

    @Override
    public void release(String bindingId) {
        redis.execute(RELEASE, Collections.singletonList(key(bindingId)), ownerToken);
        heldBindings.remove(bindingId);
    }

    @Override
    public boolean isActive(String bindingId) {
        return Boolean.TRUE.equals(redis.hasKey(key(bindingId)));
    }

    private String key(String bindingId) {
        return properties.redis().keyPrefix() + ":lease:" + bindingId;
    }
}
