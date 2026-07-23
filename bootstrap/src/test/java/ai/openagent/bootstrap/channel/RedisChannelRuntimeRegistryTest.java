package ai.openagent.bootstrap.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.openagent.bootstrap.channel.config.ChannelProperties;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisChannelRuntimeRegistryTest {

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    @BeforeAll
    static void connect() {
        int port = Integer.getInteger("openagent.test.redis.port", 0);
        assumeTrue(port > 0, "set -Dopenagent.test.redis.port to run Redis integration tests");
        connectionFactory = new LettuceConnectionFactory("127.0.0.1", port);
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void staleOwnerCannotDeleteOrUpdateReplacementHeartbeat() {
        ChannelProperties properties = properties("runtime-" + System.nanoTime());
        RedisChannelRuntimeRegistry stale = new RedisChannelRuntimeRegistry(redis, properties);
        RedisChannelRuntimeRegistry replacement = new RedisChannelRuntimeRegistry(redis, properties);

        stale.report("binding-1", "connected", "");
        replacement.report("binding-1", "connected", "");
        stale.recordMessage("binding-1", 123L);
        stale.remove("binding-1");

        ChannelRuntimeSnapshot snapshot = replacement.find("binding-1").orElseThrow();
        assertEquals(replacement.ownerId(), snapshot.ownerId());
        assertNull(snapshot.lastMessageAt());

        replacement.recordMessage("binding-1", 456L);
        assertEquals(456L, replacement.find("binding-1").orElseThrow().lastMessageAt());
        replacement.remove("binding-1");
    }

    private static ChannelProperties properties(String prefix) {
        return new ChannelProperties(
                "redis",
                Set.of("channel-ingress"),
                new ChannelProperties.Redis(prefix, Duration.ofMillis(100), Duration.ofMillis(50), 10),
                new ChannelProperties.Lease(Duration.ofSeconds(2), Duration.ofMillis(500)));
    }
}
