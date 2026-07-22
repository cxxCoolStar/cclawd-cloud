package ai.openagent.bootstrap.channel.wechat;

import ai.openagent.bootstrap.channel.ChannelInboundMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/** Minimal iLink protocol client for QR login, text polling, and text replies. */
public class WechatILinkClient {

    private static final Duration LOGIN_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(40);
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final WechatCredentials credentials;
    private final String wechatUin;

    public WechatILinkClient(ObjectMapper objectMapper, WechatCredentials credentials) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), objectMapper, credentials);
    }

    WechatILinkClient(HttpClient httpClient, ObjectMapper objectMapper, WechatCredentials credentials) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.credentials = credentials;
        String value = Long.toUnsignedString(java.util.concurrent.ThreadLocalRandom.current().nextLong());
        this.wechatUin = Base64.getEncoder().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public PollResult poll(String cursor) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("get_updates_buf", value(cursor));
        request.set("base_info", baseInfo());
        JsonNode response = post("/ilink/bot/getupdates", request, POLL_TIMEOUT);
        List<ChannelInboundMessage> messages = new ArrayList<>();
        for (JsonNode message : response.path("msgs")) {
            parseInbound(message).ifPresent(messages::add);
        }
        return new PollResult(
                response.path("ret").asInt(),
                response.path("errcode").asInt(),
                response.path("errmsg").asText(""),
                response.path("get_updates_buf").asText(""),
                messages);
    }

    public void sendText(String chatId, String text, String contextToken) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("from_user_id", credentials.accountId());
        message.put("to_user_id", chatId);
        message.put("client_id", UUID.randomUUID().toString());
        message.put("message_type", 2);
        message.put("message_state", 2);
        if (contextToken != null && !contextToken.isBlank()) {
            message.put("context_token", contextToken);
        }
        ArrayNode items = message.putArray("item_list");
        ObjectNode item = items.addObject();
        item.put("type", 1);
        item.putObject("text_item").put("text", plainText(text));
        ObjectNode request = objectMapper.createObjectNode();
        request.set("msg", message);
        request.set("base_info", baseInfo());
        JsonNode response = post("/ilink/bot/sendmessage", request, SEND_TIMEOUT);
        if (response.path("ret").asInt() != 0) {
            throw new IllegalStateException("iLink send failed: " + response.path("errmsg").asText("unknown"));
        }
    }

    public static QrCode fetchQrCode(HttpClient client, ObjectMapper mapper, String baseUrl) {
        JsonNode response = get(client, mapper, baseUrl + "/ilink/bot/get_bot_qrcode?bot_type=3", LOGIN_TIMEOUT);
        String code = response.path("qrcode").asText("");
        if (code.isBlank()) {
            throw new IllegalStateException("iLink returned an empty QR code");
        }
        return new QrCode(code, response.path("qrcode_img_content").asText(""));
    }

    public static QrStatus pollQrStatus(HttpClient client, ObjectMapper mapper, String baseUrl, String code) {
        String encoded = java.net.URLEncoder.encode(code, java.nio.charset.StandardCharsets.UTF_8);
        JsonNode response = get(
                client, mapper, baseUrl + "/ilink/bot/get_qrcode_status?qrcode=" + encoded, LOGIN_TIMEOUT);
        return new QrStatus(
                response.path("status").asText("wait"),
                response.path("bot_token").asText(""),
                response.path("ilink_bot_id").asText(""),
                response.path("baseurl").asText(""),
                response.path("ilink_user_id").asText(""));
    }

    private java.util.Optional<ChannelInboundMessage> parseInbound(JsonNode message) {
        if (message.path("message_type").asInt() != 1 || message.path("message_state").asInt() != 2) {
            return java.util.Optional.empty();
        }
        String text = "";
        for (JsonNode item : message.path("item_list")) {
            if (item.path("type").asInt() == 1 && !item.path("text_item").path("text").asText("").isBlank()) {
                text = item.path("text_item").path("text").asText();
            }
        }
        String from = message.path("from_user_id").asText("");
        String messageId = message.path("message_id").asText("");
        if (text.isBlank() || from.isBlank() || messageId.isBlank()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new ChannelInboundMessage(
                "wechat", credentials.accountId(), from, from, messageId, text,
                message.path("context_token").asText("")));
    }

    private JsonNode post(String path, JsonNode body, Duration timeout) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(credentials.baseUrl() + path))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("AuthorizationType", "ilink_bot_token")
                    .header("Authorization", "Bearer " + credentials.botToken())
                    .header("X-WECHAT-UIN", wechatUin)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            return response(httpClient.send(request, HttpResponse.BodyHandlers.ofString()));
        } catch (IOException error) {
            throw new IllegalStateException("iLink request failed", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("iLink request interrupted", error);
        }
    }

    private JsonNode response(HttpResponse<String> response) throws IOException {
        if (response.statusCode() != 200) {
            throw new IllegalStateException("iLink HTTP " + response.statusCode() + ": " + response.body());
        }
        return objectMapper.readTree(response.body());
    }

    private static JsonNode get(HttpClient client, ObjectMapper mapper, String url, Duration timeout) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("iLink HTTP " + response.statusCode() + ": " + response.body());
            }
            return mapper.readTree(response.body());
        } catch (IOException error) {
            throw new IllegalStateException("iLink request failed", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("iLink request interrupted", error);
        }
    }

    private ObjectNode baseInfo() {
        return objectMapper.createObjectNode().put("channel_version", "1.0.0");
    }

    static String plainText(String text) {
        return value(text).replace("```", "").replace("**", "").replace("__", "").replace("`", "");
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    public record PollResult(int ret, int errorCode, String errorMessage, String cursor,
            List<ChannelInboundMessage> messages) {}

    public record QrCode(String code, String imageContent) {}

    public record QrStatus(String status, String botToken, String accountId, String baseUrl,
            String ilinkUserId) {}
}
