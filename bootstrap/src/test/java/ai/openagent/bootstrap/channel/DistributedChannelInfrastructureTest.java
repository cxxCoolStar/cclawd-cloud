package ai.openagent.bootstrap.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.openagent.bootstrap.channel.config.ChannelProperties;
import ai.openagent.bootstrap.persistence.ChannelBindingRecord;
import ai.openagent.bootstrap.persistence.ChannelConversationRecord;
import ai.openagent.bootstrap.persistence.ChannelDispatchRepository;
import ai.openagent.bootstrap.persistence.ChannelInboxRecord;
import ai.openagent.bootstrap.persistence.ChannelMessageRepository;
import ai.openagent.bootstrap.persistence.ChannelRepository;
import ai.openagent.bootstrap.persistence.ChannelStaleDispatchRepository;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfSystemProperty(named = "openagent.test.postgres.url", matches = ".+")
@EnabledIfSystemProperty(named = "openagent.test.redis.port", matches = "[1-9][0-9]*")
class DistributedChannelInfrastructureTest {

    private static final String SCHEMA = "channel_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final String USER_ID = "distributed-user";
    private static final String PROVIDER_ID = "distributed-provider";
    private static final String AGENT_ID = "distributed-agent";

    private static DriverManagerDataSource adminDataSource;
    private static JdbcTemplate firstJdbc;
    private static ChannelRepository channelRepository;
    private static ChannelMessageRepository firstMessages;
    private static ChannelMessageRepository secondMessages;
    private static ChannelDispatchRepository dispatchRepository;
    private static ChannelStaleDispatchRepository staleRepository;
    private static TransactionTemplate firstTransaction;
    private static TransactionTemplate secondTransaction;
    private static LettuceConnectionFactory redisConnectionFactory;
    private static StringRedisTemplate redis;
    private static ChannelProperties channelProperties;
    private static ChannelBindingRecord binding;
    private static ChannelConversationRecord orderedConversation;
    private static ChannelConversationRecord staleConversation;

    @BeforeAll
    static void initializeInfrastructure() {
        String url = System.getProperty("openagent.test.postgres.url");
        String username = System.getProperty("openagent.test.postgres.username", "openagent");
        String password = System.getProperty("openagent.test.postgres.password", "openagent");
        adminDataSource = dataSource(url, username, password);
        new JdbcTemplate(adminDataSource).execute("CREATE SCHEMA " + SCHEMA);

        String schemaUrl = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA;
        DriverManagerDataSource firstDataSource = dataSource(schemaUrl, username, password);
        DriverManagerDataSource secondDataSource = dataSource(schemaUrl, username, password);
        Flyway.configure()
                .dataSource(firstDataSource)
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        firstJdbc = new JdbcTemplate(firstDataSource);
        JdbcTemplate secondJdbc = new JdbcTemplate(secondDataSource);
        firstTransaction = new TransactionTemplate(new DataSourceTransactionManager(firstDataSource));
        secondTransaction = new TransactionTemplate(new DataSourceTransactionManager(secondDataSource));
        channelRepository = new ChannelRepository(firstJdbc);
        firstMessages = new ChannelMessageRepository(firstJdbc);
        secondMessages = new ChannelMessageRepository(secondJdbc);
        dispatchRepository = new ChannelDispatchRepository(firstJdbc);
        staleRepository = new ChannelStaleDispatchRepository(firstJdbc);
        seedOwnerRecords();

        int redisPort = Integer.getInteger("openagent.test.redis.port");
        redisConnectionFactory = new LettuceConnectionFactory("127.0.0.1", redisPort);
        redisConnectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(redisConnectionFactory);
        redis.afterPropertiesSet();
        channelProperties = new ChannelProperties(
                "redis",
                Set.of("channel-ingress", "agent-worker", "channel-egress"),
                new ChannelProperties.Redis(
                        "distributed-" + System.nanoTime(), Duration.ofMillis(100), Duration.ofMillis(50), 10),
                new ChannelProperties.Lease(Duration.ofSeconds(2), Duration.ofMillis(500)));

        long now = System.currentTimeMillis();
        binding = new ChannelBindingRecord(
                "binding-1", USER_ID, AGENT_ID, "wechat", "account-1", "Distributed test",
                "{}", true, false, "{}", now, now);
        channelRepository.insertBinding(binding);
        orderedConversation = channelRepository.resolveConversation(
                binding, "chat-ordered", "chatter-1", "context-ordered");
        staleConversation = channelRepository.resolveConversation(
                binding, "chat-stale", "chatter-1", "context-stale");
    }

    @AfterAll
    static void cleanInfrastructure() {
        if (redisConnectionFactory != null) {
            redisConnectionFactory.destroy();
        }
        if (adminDataSource != null) {
            new JdbcTemplate(adminDataSource).execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    @Test
    void duplicateDeliveryAndCrossInstanceClaimsRemainOrdered() {
        ChannelInboxRecord first = accept(orderedConversation, "message-1", "first").orElseThrow();
        assertTrue(accept(orderedConversation, "message-1", "duplicate").isEmpty());
        ChannelInboxRecord second = accept(orderedConversation, "message-2", "second").orElseThrow();

        Optional<?> claimedFirst = firstTransaction.execute(status -> firstMessages.claimInbound(
                first.id(), "worker-1", System.currentTimeMillis() + 60_000L, System.currentTimeMillis()));
        assertTrue(claimedFirst.isPresent());
        Optional<?> blockedSecond = secondTransaction.execute(status -> secondMessages.claimInbound(
                second.id(), "worker-2", System.currentTimeMillis() + 60_000L, System.currentTimeMillis()));
        assertTrue(blockedSecond.isEmpty());

        firstTransaction.executeWithoutResult(status -> firstMessages.completeInbound(
                first, "run-1", orderedConversation.chatId(), "reply-1", orderedConversation.contextToken()));
        Optional<?> claimedSecond = secondTransaction.execute(status -> secondMessages.claimInbound(
                second.id(), "worker-2", System.currentTimeMillis() + 60_000L, System.currentTimeMillis()));
        assertTrue(claimedSecond.isPresent());
        assertEquals(first.sequenceNo() + 1L, second.sequenceNo());
    }

    @Test
    void stalePublishedRowRestoresADeletedRedisNotification() throws Exception {
        ChannelInboxRecord inbox = accept(staleConversation, "message-stale", "stale").orElseThrow();
        RedisChannelMessageBus firstBus = new RedisChannelMessageBus(redis, channelProperties);
        firstBus.initialize();
        firstBus.publishInbound(new ChannelInboundTask(inbox.id()));
        dispatchRepository.markInboundPublished(inbox.id(), 1L);

        String stream = channelProperties.redis().keyPrefix() + ":inbound";
        assertTrue(Boolean.TRUE.equals(redis.delete(stream)));
        RedisChannelMessageBus replacementBus = new RedisChannelMessageBus(redis, channelProperties);
        replacementBus.initialize();

        assertEquals(java.util.List.of(inbox.id()), staleRepository.listStaleInboundIds(2L, 10));
        replacementBus.publishInbound(new ChannelInboundTask(inbox.id()));
        staleRepository.touchInbound(inbox.id(), System.currentTimeMillis());
        ChannelDelivery<ChannelInboundTask> restored = replacementBus.takeInbound();
        assertEquals(inbox.id(), restored.task().inboxId());
        restored.acknowledge();
        assertFalse(staleRepository.listStaleInboundIds(2L, 10).contains(inbox.id()));
    }

    private static Optional<ChannelInboxRecord> accept(
            ChannelConversationRecord targetConversation, String messageId, String text) {
        return firstTransaction.execute(status -> firstMessages.acceptInbound(
                binding,
                targetConversation,
                new ChannelInboundMessage(
                        "wechat",
                        "account-1",
                        targetConversation.chatId(),
                        "chatter-1",
                        messageId,
                        text,
                        targetConversation.contextToken())));
    }

    private static void seedOwnerRecords() {
        long now = System.currentTimeMillis();
        firstJdbc.update("""
                INSERT INTO users
                    (id, username, email, role, display_name, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, USER_ID, USER_ID, "distributed@example.invalid", "admin", "Distributed", "active", now);
        firstJdbc.update("""
                INSERT INTO providers
                    (id, provider_type, name, api_base, api_key, model, temperature,
                     max_tokens, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, PROVIDER_ID, "openai", "Distributed", "http://localhost", "", "test", 0D, 1, now, now);
        firstJdbc.update("""
                INSERT INTO agents
                    (id, user_id, name, description, provider_id, model, system_prompt,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, AGENT_ID, USER_ID, "Distributed", "", PROVIDER_ID, "test", "test", now, now);
    }

    private static DriverManagerDataSource dataSource(String url, String username, String password) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, username, password);
        dataSource.setDriverClassName("org.postgresql.Driver");
        return dataSource;
    }
}
