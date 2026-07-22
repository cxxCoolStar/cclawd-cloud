package ai.openagent.bootstrap.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.openagent.bootstrap.channel.config.ChannelProperties;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisChannelInfrastructureTest {

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
    void unacknowledgedNotificationIsReclaimedAndAcknowledged() throws Exception {
        ChannelProperties properties = properties("bus-" + System.nanoTime());
        RedisChannelMessageBus first = new RedisChannelMessageBus(redis, properties);
        RedisChannelMessageBus second = new RedisChannelMessageBus(redis, properties);
        first.initialize();
        second.initialize();

        first.publishInbound(new ChannelInboundTask("inbox-1"));
        ChannelDelivery<ChannelInboundTask> initial = first.takeInbound();
        assertEquals("inbox-1", initial.task().inboxId());

        Thread.sleep(properties.redis().pendingIdle().toMillis() + 20L);
        ChannelDelivery<ChannelInboundTask> reclaimed = second.takeInbound();
        assertEquals("inbox-1", reclaimed.task().inboxId());
        reclaimed.acknowledge();

        String stream = properties.redis().keyPrefix() + ":inbound";
        String group = properties.redis().keyPrefix() + ":inbound-workers";
        assertEquals(0, redis.opsForStream().pending(stream, group).getTotalPendingMessages());
    }

    @Test
    void leaseRenewAndReleaseRequireTheOwnerToken() {
        ChannelProperties properties = properties("lease-" + System.nanoTime());
        RedisChannelLeaseService first = new RedisChannelLeaseService(redis, properties);
        RedisChannelLeaseService second = new RedisChannelLeaseService(redis, properties);

        assertTrue(first.acquire("binding-1"));
        assertFalse(second.acquire("binding-1"));
        assertTrue(first.renew("binding-1"));
        second.release("binding-1");
        assertFalse(second.acquire("binding-1"));
        first.release("binding-1");
        assertTrue(second.acquire("binding-1"));
    }

    private static ChannelProperties properties(String prefix) {
        return new ChannelProperties(
                "redis",
                Set.of("channel-ingress", "agent-worker", "channel-egress"),
                new ChannelProperties.Redis(prefix, Duration.ofMillis(100), Duration.ofMillis(50), 10),
                new ChannelProperties.Lease(Duration.ofSeconds(2), Duration.ofMillis(500)));
    }
}
