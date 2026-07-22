package ai.openagent.bootstrap.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.openagent.bootstrap.OpenAgentApplication;
import ai.openagent.bootstrap.identity.IdentityConstant;
import ai.openagent.bootstrap.persistence.ChannelBindingRecord;
import ai.openagent.bootstrap.persistence.ChannelConversationRecord;
import ai.openagent.bootstrap.persistence.ChannelRepository;
import ai.openagent.bootstrap.persistence.ChatMessageRecord;
import ai.openagent.bootstrap.persistence.ChatSessionRecord;
import ai.openagent.bootstrap.persistence.ChatSessionRepository;
import ai.openagent.infra.ai.LLMService;
import ai.openagent.infra.ai.model.ModelResponse;
import ai.openagent.infra.ai.model.TokenUsage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        classes = OpenAgentApplication.class,
        properties = {
            "openagent.model.api-key=test-key",
            "openagent.model.name=test-model",
            "openagent.memory.auto-persist-enabled=false"
        })
@Import(ChannelRuntimeBridgeTest.ScriptedModelConfiguration.class)
class ChannelRuntimeBridgeTest {

    private static final String DATABASE_ID = UUID.randomUUID().toString();

    @Autowired
    private ChannelRepository channelRepository;

    @Autowired
    private ChatSessionRepository sessionRepository;

    @Autowired
    private ChannelRuntimeManager runtimeManager;

    @Autowired
    private ObjectMapper objectMapper;

    private final AtomicInteger polls = new AtomicInteger();
    private final AtomicInteger sends = new AtomicInteger();
    private final AtomicReference<JsonNode> sentBody = new AtomicReference<>();
    private CountDownLatch sent;
    private HttpServer server;
    private ChannelBindingRecord binding;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:target/channel-runtime-bridge-" + DATABASE_ID + ".db");
    }

    @BeforeEach
    void startServer() throws IOException {
        ScriptedModelConfiguration.RUNS.set(0);
        sent = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ilink/bot/getupdates", this::getUpdates);
        server.createContext("/ilink/bot/sendmessage", this::sendMessage);
        server.start();
    }

    @AfterEach
    void stopRuntime() {
        if (binding != null) {
            runtimeManager.stop(binding.channelType(), binding.accountId());
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void oneInboundMessageProducesOneRunAndOneReply() throws Exception {
        String accountId = "bot-" + UUID.randomUUID();
        long now = System.currentTimeMillis();
        String credentials = objectMapper.writeValueAsString(Map.of(
                "botToken", "token",
                "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort(),
                "ilinkUserId", "user-id"));
        binding = new ChannelBindingRecord(
                UUID.randomUUID().toString(), IdentityConstant.LOCAL_USER_ID, "default",
                "wechat", accountId, accountId, credentials, true, false, "{}", now, now);
        channelRepository.insertBinding(binding);

        runtimeManager.start(binding);

        assertTrue(sent.await(10, TimeUnit.SECONDS), "channel reply was not sent");
        runtimeManager.stop(binding.channelType(), binding.accountId());

        assertEquals(1, ScriptedModelConfiguration.RUNS.get());
        assertEquals(1, sends.get());
        assertEquals("sender-1", sentBody.get().path("msg").path("to_user_id").asText());
        assertEquals("runtime reply",
                sentBody.get().path("msg").path("item_list").get(0).path("text_item").path("text").asText());

        ChannelConversationRecord conversation = channelRepository
                .findConversation(binding.id(), "sender-1")
                .orElseThrow();
        ChatSessionRecord session = sessionRepository
                .listSessions(IdentityConstant.LOCAL_USER_ID, "default").stream()
                .filter(candidate -> candidate.id().equals(conversation.sessionId()))
                .findFirst()
                .orElseThrow();
        assertEquals("wechat", session.channel());
        java.util.List<ChatMessageRecord> messages = sessionRepository.listMessages(
                IdentityConstant.LOCAL_USER_ID, "default", conversation.sessionId());
        assertEquals(2, messages.size());
        assertEquals("message text", messages.get(0).content());
        assertEquals("runtime reply", messages.get(1).content());
    }

    private void getUpdates(HttpExchange exchange) throws IOException {
        int poll = polls.incrementAndGet();
        String messages = poll == 1
                ? """
                  {"message_id":101,"from_user_id":"sender-1","message_type":1,"message_state":2,
                   "context_token":"ctx-1","item_list":[{"type":1,"text_item":{"text":"message text"}}]},
                  {"message_id":101,"from_user_id":"sender-1","message_type":1,"message_state":2,
                   "context_token":"ctx-1","item_list":[{"type":1,"text_item":{"text":"message text"}}]}
                  """
                : "";
        json(exchange, "{\"ret\":0,\"get_updates_buf\":\"cursor-" + poll
                + "\",\"msgs\":[" + messages + "]}");
    }

    private void sendMessage(HttpExchange exchange) throws IOException {
        sentBody.set(objectMapper.readTree(exchange.getRequestBody()));
        sends.incrementAndGet();
        json(exchange, "{\"ret\":0}");
        sent.countDown();
    }

    private static void json(HttpExchange exchange, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, data.length);
        exchange.getResponseBody().write(data);
        exchange.close();
    }

    @TestConfiguration
    static class ScriptedModelConfiguration {

        static final AtomicInteger RUNS = new AtomicInteger();

        @Bean
        @Primary
        LLMService scriptedLlmService() {
            return (request, listener) -> {
                RUNS.incrementAndGet();
                return new ModelResponse.Text("runtime reply", TokenUsage.ZERO, "");
            };
        }
    }
}
