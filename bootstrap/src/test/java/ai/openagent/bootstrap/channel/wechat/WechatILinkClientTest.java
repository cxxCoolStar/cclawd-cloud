package ai.openagent.bootstrap.channel.wechat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WechatILinkClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<JsonNode> sentBody = new AtomicReference<>();
    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ilink/bot/getupdates", this::getUpdates);
        server.createContext("/ilink/bot/sendmessage", this::sendMessage);
        server.createContext("/ilink/bot/get_bot_qrcode", exchange -> json(exchange, """
                {"qrcode":"qr-token","qrcode_img_content":"image-data"}
                """));
        server.createContext("/ilink/bot/get_qrcode_status", exchange -> json(exchange, """
                {"status":"confirmed","bot_token":"token","ilink_bot_id":"bot@im.bot",
                 "baseurl":"http://example.test","ilink_user_id":"user-id"}
                """));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void pollFiltersNonFinalMessagesAndPreservesConversationIdentity() {
        WechatILinkClient client = client();
        WechatILinkClient.PollResult result = client.poll("cursor-before");

        assertEquals("cursor-after", result.cursor());
        assertEquals(1, result.messages().size());
        assertEquals("sender-1", result.messages().get(0).chatId());
        assertEquals("message text", result.messages().get(0).text());
        assertEquals("ctx-1", result.messages().get(0).contextToken());
    }

    @Test
    void sendUsesAccountContextAndRequiredAuthenticationHeaders() {
        client().sendText("sender-1", "**hello** `there`", "ctx-1");

        JsonNode body = sentBody.get();
        assertEquals("bot@im.bot", body.path("msg").path("from_user_id").asText());
        assertEquals("sender-1", body.path("msg").path("to_user_id").asText());
        assertEquals("hello there",
                body.path("msg").path("item_list").get(0).path("text_item").path("text").asText());
        assertFalse(body.path("msg").path("client_id").asText().isBlank());
    }

    @Test
    void qrEndpointsUseTheFrontendContract() {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        WechatILinkClient.QrCode qr = WechatILinkClient.fetchQrCode(httpClient, objectMapper, baseUrl);
        WechatILinkClient.QrStatus status =
                WechatILinkClient.pollQrStatus(httpClient, objectMapper, baseUrl, qr.code());

        assertEquals("qr-token", qr.code());
        assertEquals("confirmed", status.status());
        assertEquals("bot@im.bot", status.accountId());
    }

    @Test
    void adapterBackoffMatchesBoundedReferenceBehavior() {
        assertEquals(Duration.ofSeconds(3), WechatChannelAdapter.backoff(1));
        assertEquals(Duration.ofSeconds(6), WechatChannelAdapter.backoff(2));
        assertEquals(Duration.ofSeconds(60), WechatChannelAdapter.backoff(20));
    }

    private WechatILinkClient client() {
        return new WechatILinkClient(
                objectMapper, new WechatCredentials("token", "bot@im.bot", baseUrl, "user-id"));
    }

    private void getUpdates(HttpExchange exchange) throws IOException {
        assertEquals("ilink_bot_token", exchange.getRequestHeaders().getFirst("AuthorizationType"));
        assertEquals("Bearer token", exchange.getRequestHeaders().getFirst("Authorization"));
        assertFalse(exchange.getRequestHeaders().getFirst("X-WECHAT-UIN").isBlank());
        JsonNode request = objectMapper.readTree(exchange.getRequestBody());
        assertEquals("cursor-before", request.path("get_updates_buf").asText());
        json(exchange, """
                {"ret":0,"get_updates_buf":"cursor-after","msgs":[
                  {"message_id":101,"from_user_id":"sender-1","message_type":1,"message_state":2,
                   "context_token":"ctx-1","item_list":[{"type":1,"text_item":{"text":"message text"}}]},
                  {"message_id":102,"from_user_id":"sender-1","message_type":2,"message_state":2,
                   "item_list":[{"type":1,"text_item":{"text":"bot echo"}}]},
                  {"message_id":103,"from_user_id":"sender-1","message_type":1,"message_state":1,
                   "item_list":[{"type":1,"text_item":{"text":"partial"}}]}
                ]}
                """);
    }

    private void sendMessage(HttpExchange exchange) throws IOException {
        assertEquals("Bearer token", exchange.getRequestHeaders().getFirst("Authorization"));
        sentBody.set(objectMapper.readTree(exchange.getRequestBody()));
        json(exchange, "{\"ret\":0}");
    }

    private static void json(HttpExchange exchange, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, data.length);
        exchange.getResponseBody().write(data);
        exchange.close();
    }
}
