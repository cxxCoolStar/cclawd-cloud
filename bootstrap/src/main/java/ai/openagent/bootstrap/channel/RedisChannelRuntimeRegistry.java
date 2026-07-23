package ai.openagent.bootstrap.channel;

import ai.openagent.bootstrap.channel.config.ChannelProperties;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "openagent.channel.bus", havingValue = "redis")
public class RedisChannelRuntimeRegistry implements ChannelRuntimeRegistry {

    private static final String OWNER = "ownerId";
    private static final DefaultRedisScript<Long> REMOVE_IF_OWNER = new DefaultRedisScript<>("""
            if redis.call('hget', KEYS[1], 'ownerId') == ARGV[1] then
              return redis.call('del', KEYS[1])
            end
            return 0
            """, Long.class);
    private static final DefaultRedisScript<Long> MESSAGE_IF_OWNER = new DefaultRedisScript<>("""
            if redis.call('hget', KEYS[1], 'ownerId') == ARGV[1] then
              redis.call('hset', KEYS[1], 'lastMessageAt', ARGV[2])
              return 1
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ChannelProperties properties;
    private final String ownerId = hostName() + "-" + UUID.randomUUID().toString().substring(0, 8);

    public RedisChannelRuntimeRegistry(StringRedisTemplate redis, ChannelProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public String ownerId() {
        return ownerId;
    }

    @Override
    public void report(String bindingId, String adapterStatus, String lastError) {
        String key = key(bindingId);
        redis.opsForHash().putAll(key, Map.of(
                OWNER, ownerId,
                "adapterStatus", value(adapterStatus),
                "heartbeatAt", Long.toString(System.currentTimeMillis()),
                "lastError", value(lastError)));
        redis.expire(key, runtimeTtl());
    }

    @Override
    public void recordMessage(String bindingId, long receivedAt) {
        redis.execute(MESSAGE_IF_OWNER, Collections.singletonList(key(bindingId)),
                ownerId, Long.toString(receivedAt));
    }

    @Override
    public Optional<ChannelRuntimeSnapshot> find(String bindingId) {
        Map<Object, Object> values = redis.opsForHash().entries(key(bindingId));
        if (values.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ChannelRuntimeSnapshot(
                text(values, OWNER),
                text(values, "adapterStatus"),
                number(values, "heartbeatAt", 0L),
                nullableNumber(values, "lastMessageAt"),
                text(values, "lastError")));
    }

    @Override
    public void remove(String bindingId) {
        redis.execute(REMOVE_IF_OWNER, Collections.singletonList(key(bindingId)), ownerId);
    }

    private String key(String bindingId) {
        return properties.redis().keyPrefix() + ":runtime:" + bindingId;
    }

    private Duration runtimeTtl() {
        return properties.lease().ttl().multipliedBy(2);
    }

    private static String text(Map<Object, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? "" : value.toString();
    }

    private static long number(Map<Object, Object> values, String key, long fallback) {
        try {
            return Long.parseLong(text(values, key));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static Long nullableNumber(Map<Object, Object> values, String key) {
        String value = text(values, key);
        if (value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException ignored) {
            return "channel";
        }
    }
}
