package ai.openagent.bootstrap.chat.service.impl;

import ai.openagent.bootstrap.chat.controller.vo.ChatHistoryVO;
import ai.openagent.bootstrap.chat.controller.vo.ChatMessageVO;
import ai.openagent.bootstrap.chat.controller.vo.ChatSessionListVO;
import ai.openagent.bootstrap.chat.controller.vo.ChatSessionVO;
import ai.openagent.bootstrap.chat.event.ChatEventHub;
import ai.openagent.bootstrap.chat.gateway.ChatModelGateway;
import ai.openagent.bootstrap.chat.service.ChatService;
import ai.openagent.bootstrap.identity.IdentityConstant;
import ai.openagent.bootstrap.persistence.AgentRecord;
import ai.openagent.bootstrap.persistence.AgentRepository;
import ai.openagent.bootstrap.persistence.ChatSessionRepository;
import ai.openagent.bootstrap.persistence.ProviderRecord;
import ai.openagent.bootstrap.persistence.ProviderRepository;
import ai.openagent.bootstrap.persistence.SessionEventRecord;
import ai.openagent.framework.errorcode.BaseErrorCode;
import ai.openagent.framework.exception.ClientException;
import ai.openagent.framework.exception.ServiceException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 聊天服务实现
 *
 * <p>
 * 回合执行流程：开启回合（校验 + 落库用户消息）→ 流式调用模型网关 →
 * 逐段广播 content_delta 瞬时事件 → 完成后落库助手消息并发布持久化的
 * content/done 事件；异常时发布 error + done 事件，保证前端回合必然收敛
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private final AgentRepository agentRepository;
    private final ProviderRepository providerRepository;
    private final ChatSessionRepository sessionRepository;
    private final ChatModelGateway modelGateway;
    private final ChatEventHub eventHub;
    private final ObjectMapper objectMapper;

    @Override
    public Turn beginTurn(String agentId, String sessionId, String message) {
        if (sessionId == null || sessionId.isBlank() || sessionId.length() > 128) {
            throw new ClientException("valid sessionId required");
        }
        if (message == null || message.isBlank()) {
            throw new ClientException("message required");
        }
        AgentRecord agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new ClientException("agent not found", BaseErrorCode.RESOURCE_NOT_FOUND));
        ProviderRecord provider = providerRepository.findById(agent.providerId())
                .orElseThrow(() -> new ServiceException("provider not found", BaseErrorCode.SERVICE_UNAVAILABLE_ERROR));
        String userId = IdentityConstant.LOCAL_USER_ID;
        sessionRepository.ensureSession(userId, agentId, sessionId, message);
        sessionRepository.appendMessage(userId, agentId, sessionId, "user", message, provider.type(), agent.model());
        return new Turn(userId, agent, provider, sessionId, sessionRepository.listMessages(userId, agentId, sessionId));
    }

    @Override
    public void stream(Turn turn) {
        long startedAt = System.currentTimeMillis();
        log.info("[chat] 开始流式回合，agentId={}, sessionId={}, model={}",
                turn.agent().id(), turn.sessionId(), turn.agent().model());
        try {
            String answer = modelGateway.stream(
                    turn.provider(),
                    turn.agent(),
                    turn.messages(),
                    delta -> publishTransient(turn, "content_delta", Map.of("delta", delta)));
            sessionRepository.appendMessage(
                    turn.userId(),
                    turn.agent().id(),
                    turn.sessionId(),
                    "assistant",
                    answer,
                    turn.provider().type(),
                    turn.agent().model());
            publishPersistent(turn, "content", Map.of("content", answer));
            publishPersistent(turn, "done", Map.of());
            log.info("[chat] 回合完成，agentId={}, sessionId={}, 耗时 {}ms",
                    turn.agent().id(), turn.sessionId(), System.currentTimeMillis() - startedAt);
        } catch (Exception error) {
            String message = rootMessage(error);
            log.error("[chat] 回合失败，agentId={}, sessionId={}, 耗时 {}ms",
                    turn.agent().id(), turn.sessionId(), System.currentTimeMillis() - startedAt, error);
            publishPersistent(turn, "error", Map.of("message", message));
            publishPersistent(turn, "done", Map.of());
        }
    }

    @Override
    public ChatHistoryVO history(String agentId, String sessionId) {
        String userId = IdentityConstant.LOCAL_USER_ID;
        List<ChatMessageVO> history = sessionRepository.listMessages(userId, agentId, sessionId).stream()
                .map(ChatMessageVO::from)
                .toList();
        return new ChatHistoryVO(history, sessionRepository.latestEventSequence(userId, agentId, sessionId));
    }

    @Override
    public ChatSessionListVO sessions(String agentId) {
        List<ChatSessionVO> sessions =
                sessionRepository.listSessions(IdentityConstant.LOCAL_USER_ID, agentId).stream()
                        .map(ChatSessionVO::from)
                        .toList();
        return new ChatSessionListVO(sessions);
    }

    @Override
    public List<Map<String, Object>> replayEventsSince(String agentId, String sessionId, long since) {
        return sessionRepository.listEventsSince(IdentityConstant.LOCAL_USER_ID, agentId, sessionId, since).stream()
                .map(this::decode)
                .toList();
    }

    /**
     * 将持久化事件解码为可下发结构（解码失败降级为 error 事件）
     */
    private Map<String, Object> decode(SessionEventRecord event) {
        try {
            Map<String, Object> data = objectMapper.readValue(event.eventData(), new TypeReference<>() {});
            return event(event.seq(), event.eventType(), data);
        } catch (JsonProcessingException error) {
            log.warn("[chat] 存量事件解码失败，seq={}, type={}", event.seq(), event.eventType(), error);
            return event(event.seq(), "error", Map.of("message", "stored event could not be decoded"));
        }
    }

    /**
     * 广播不落库的瞬时事件（seq=-1，如高频 content_delta）
     */
    private void publishTransient(Turn turn, String type, Map<String, Object> data) {
        Map<String, Object> event = event(-1, type, data);
        eventHub.broadcast(turn.agent().id(), turn.sessionId(), event);
    }

    /**
     * 持久化事件到 session_events 并广播（带会话内递增 seq，供断线回放）
     */
    private void publishPersistent(Turn turn, String type, Map<String, Object> data) {
        try {
            SessionEventRecord stored = sessionRepository.appendEvent(
                    turn.userId(),
                    turn.agent().id(),
                    turn.sessionId(),
                    type,
                    objectMapper.writeValueAsString(data));
            Map<String, Object> event = event(stored.seq(), type, data);
            eventHub.broadcast(turn.agent().id(), turn.sessionId(), event);
        } catch (JsonProcessingException error) {
            throw new ServiceException("could not persist chat event", error, BaseErrorCode.SERVICE_ERROR);
        }
    }

    private static Map<String, Object> event(long seq, String type, Map<String, Object> data) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("seq", seq);
        event.put("type", type);
        event.put("data", data);
        return event;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
