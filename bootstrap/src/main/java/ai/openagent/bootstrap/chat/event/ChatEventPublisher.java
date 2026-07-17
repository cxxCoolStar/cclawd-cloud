package ai.openagent.bootstrap.chat.event;

import ai.openagent.bootstrap.persistence.ChatSessionRepository;
import ai.openagent.bootstrap.persistence.SessionEventRecord;
import ai.openagent.framework.errorcode.BaseErrorCode;
import ai.openagent.framework.exception.ServiceException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 聊天事件发布器（V2 方案 8.2 发布顺序）
 *
 * <p>
 * 可恢复事件先写 session_events 获得 seq，再广播到 ChatEventHub；
 * 瞬时事件（content_delta 等高频增量）以 seq=-1 直接广播不入库。
 * 聊天回合与 Agent 运行共用本发布器，保证事件顺序语义一致
 * </p>
 */
@Component
@RequiredArgsConstructor
public class ChatEventPublisher {

    private final ChatSessionRepository sessionRepository;
    private final ChatEventHub eventHub;
    private final ObjectMapper objectMapper;

    /**
     * 广播不落库的瞬时事件（seq=-1，如高频 content_delta）
     */
    public void publishTransient(String agentId, String sessionId, String type, Map<String, Object> data) {
        eventHub.broadcast(agentId, sessionId, envelope(-1, type, data));
    }

    /**
     * 持久化事件并广播（带会话内递增 seq，供断线回放）
     */
    public void publishPersistent(
            String userId, String agentId, String sessionId, String type, Map<String, Object> data) {
        try {
            SessionEventRecord stored = sessionRepository.appendEvent(
                    userId, agentId, sessionId, type, objectMapper.writeValueAsString(data));
            eventHub.broadcast(agentId, sessionId, envelope(stored.seq(), type, data));
        } catch (JsonProcessingException error) {
            throw new ServiceException("could not persist chat event", error, BaseErrorCode.SERVICE_ERROR);
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
