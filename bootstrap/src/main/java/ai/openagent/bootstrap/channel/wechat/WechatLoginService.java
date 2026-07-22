package ai.openagent.bootstrap.channel.wechat;

import ai.openagent.bootstrap.channel.ChannelRuntimeManager;
import ai.openagent.bootstrap.persistence.ChannelBindingRecord;
import ai.openagent.bootstrap.persistence.ChannelRepository;
import ai.openagent.framework.errorcode.BaseErrorCode;
import ai.openagent.framework.exception.ClientException;
import ai.openagent.framework.exception.ServiceException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/** Manages short-lived iLink QR sessions and persists confirmed accounts. */
@Service
public class WechatLoginService {

    private static final long LOGIN_TTL_MILLIS = Duration.ofMinutes(5).toMillis();

    private final ChannelRepository channelRepository;
    private final ChannelRuntimeManager runtimeManager;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Map<String, LoginSession> sessions = new ConcurrentHashMap<>();

    public WechatLoginService(
            ChannelRepository channelRepository,
            ChannelRuntimeManager runtimeManager,
            ObjectMapper objectMapper) {
        this.channelRepository = channelRepository;
        this.runtimeManager = runtimeManager;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public LoginStart start(String ownerUserId, String agentId) {
        cleanup();
        try {
            WechatILinkClient.QrCode qr = WechatILinkClient.fetchQrCode(
                    httpClient, objectMapper, WechatCredentials.DEFAULT_BASE_URL);
            sessions.put(qr.code(), new LoginSession(
                    qr.code(), ownerUserId, agentId, System.currentTimeMillis()));
            return new LoginStart(qr.code(), qr.code(), qr.imageContent());
        } catch (RuntimeException error) {
            throw new ServiceException(
                    "could not start WeChat login", error, BaseErrorCode.SERVICE_UNAVAILABLE_ERROR);
        }
    }

    public LoginStatus poll(String ownerUserId, String agentId, String sessionId) {
        cleanup();
        LoginSession session = sessions.get(sessionId);
        if (session == null
                || !session.ownerUserId().equals(ownerUserId)
                || !session.agentId().equals(agentId)) {
            throw new ClientException("WeChat login session not found", BaseErrorCode.RESOURCE_NOT_FOUND);
        }
        WechatILinkClient.QrStatus status;
        try {
            status = WechatILinkClient.pollQrStatus(
                    httpClient, objectMapper, WechatCredentials.DEFAULT_BASE_URL, session.qrCode());
        } catch (RuntimeException error) {
            throw new ServiceException(
                    "could not query WeChat login", error, BaseErrorCode.SERVICE_UNAVAILABLE_ERROR);
        }
        if ("expired".equals(status.status())) {
            sessions.remove(sessionId);
            return new LoginStatus("expired", false, "");
        }
        if (!"confirmed".equals(status.status())) {
            return new LoginStatus(status.status(), false, "");
        }
        if (status.botToken().isBlank() || status.accountId().isBlank()) {
            throw new ServiceException(
                    "iLink confirmed without credentials", BaseErrorCode.SERVICE_UNAVAILABLE_ERROR);
        }
        WechatCredentials credentials = new WechatCredentials(
                status.botToken(), status.accountId(), status.baseUrl(), status.ilinkUserId());
        ChannelBindingRecord binding = binding(ownerUserId, agentId, credentials);
        channelRepository.upsertBinding(binding);
        ChannelBindingRecord stored = channelRepository
                .findEnabledBinding("wechat", credentials.accountId())
                .orElse(binding);
        runtimeManager.start(stored);
        sessions.remove(sessionId);
        return new LoginStatus("confirmed", true, credentials.accountId());
    }

    private ChannelBindingRecord binding(
            String ownerUserId, String agentId, WechatCredentials credentials) {
        long now = System.currentTimeMillis();
        String id = channelRepository
                .findOwnedBinding(ownerUserId, agentId, "wechat", credentials.accountId())
                .map(ChannelBindingRecord::id)
                .orElseGet(() -> UUID.randomUUID().toString());
        try {
            String credentialsJson = objectMapper.writeValueAsString(Map.of(
                    "botToken", credentials.botToken(),
                    "baseUrl", credentials.baseUrl(),
                    "ilinkUserId", credentials.ilinkUserId()));
            return new ChannelBindingRecord(
                    id, ownerUserId, agentId, "wechat", credentials.accountId(),
                    credentials.accountId(), credentialsJson, true, false, "{}", now, now);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("could not encode WeChat credentials", error);
        }
    }

    private void cleanup() {
        long cutoff = System.currentTimeMillis() - LOGIN_TTL_MILLIS;
        sessions.entrySet().removeIf(entry -> entry.getValue().createdAt() < cutoff);
    }

    public record LoginStart(String sessionId, String qrCode, String qrCodeImg) {}

    public record LoginStatus(String status, boolean connected, String accountId) {}

    private record LoginSession(String qrCode, String ownerUserId, String agentId, long createdAt) {}
}
