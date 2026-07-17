package ai.openagent.bootstrap.chat.service.impl;

import ai.openagent.bootstrap.chat.controller.vo.ChatHistoryVO;
import ai.openagent.bootstrap.chat.controller.vo.ChatMessageVO;
import ai.openagent.bootstrap.chat.controller.vo.ChatSessionListVO;
import ai.openagent.bootstrap.chat.controller.vo.ChatSessionVO;
import ai.openagent.bootstrap.chat.service.ChatService;
import ai.openagent.bootstrap.identity.IdentityConstant;
import ai.openagent.bootstrap.persistence.ChatSessionRepository;
import ai.openagent.bootstrap.persistence.SessionEventRecord;
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
 * 聊天查询服务实现（历史 / 会话列表 / 事件回放）
 *
 * <p>
 * 回合执行已迁往 agentrun 域（AgentRunCoordinator + AgentKernel）；
 * 历史消息装配包含工具分组所需的 toolCalls / toolCallId / metadata
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private final ChatSessionRepository sessionRepository;
    private final ObjectMapper objectMapper;

    @Override
    public ChatHistoryVO history(String agentId, String sessionId) {
        String userId = IdentityConstant.LOCAL_USER_ID;
        List<ChatMessageVO> history = sessionRepository.listMessages(userId, agentId, sessionId).stream()
                .map(record -> ChatMessageVO.from(record, objectMapper))
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
            return envelope(event.seq(), event.eventType(), data);
        } catch (JsonProcessingException error) {
            log.warn("[chat] 存量事件解码失败，seq={}, type={}", event.seq(), event.eventType(), error);
            return envelope(event.seq(), "error", Map.of("message", "stored event could not be decoded"));
        }
    }

    private static Map<String, Object> envelope(long seq, String type, Map<String, Object> data) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("seq", seq);
        event.put("type", type);
        event.put("data", data);
        return event;
    }
}
