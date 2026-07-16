package ai.openagent.bootstrap.chat;

import ai.openagent.bootstrap.persistence.AgentRecord;
import ai.openagent.bootstrap.persistence.ChatMessageRecord;
import ai.openagent.bootstrap.persistence.OpenAgentStore;
import ai.openagent.bootstrap.persistence.ProviderRecord;
import ai.openagent.bootstrap.persistence.SessionEventRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ChatService {

    private final OpenAgentStore store;
    private final ChatModelGateway modelGateway;
    private final ChatEventHub eventHub;
    private final ObjectMapper objectMapper;

    public ChatService(
            OpenAgentStore store,
            ChatModelGateway modelGateway,
            ChatEventHub eventHub,
            ObjectMapper objectMapper) {
        this.store = store;
        this.modelGateway = modelGateway;
        this.eventHub = eventHub;
        this.objectMapper = objectMapper;
    }

    public Turn beginTurn(String agentId, String sessionId, String message) {
        if (sessionId == null || sessionId.isBlank() || sessionId.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "valid sessionId required");
        }
        if (message == null || message.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message required");
        }
        AgentRecord agent = store.findAgent(agentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "agent not found"));
        ProviderRecord provider = store.findProvider(agent.providerId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "provider not found"));
        String userId = OpenAgentStore.LOCAL_USER_ID;
        store.ensureSession(userId, agentId, sessionId, message);
        store.appendMessage(userId, agentId, sessionId, "user", message, provider.type(), agent.model());
        return new Turn(userId, agent, provider, sessionId, store.listMessages(userId, agentId, sessionId));
    }

    public void stream(Turn turn, EventConsumer consumer) {
        try {
            String answer = modelGateway.stream(
                    turn.provider(),
                    turn.agent(),
                    turn.messages(),
                    delta -> publishTransient(turn, "content_delta", Map.of("delta", delta), consumer));
            store.appendMessage(
                    turn.userId(),
                    turn.agent().id(),
                    turn.sessionId(),
                    "assistant",
                    answer,
                    turn.provider().type(),
                    turn.agent().model());
            publishPersistent(turn, "content", Map.of("content", answer), consumer);
            publishPersistent(turn, "done", Map.of(), consumer);
        } catch (Exception error) {
            String message = rootMessage(error);
            publishPersistent(turn, "error", Map.of("message", message), consumer);
            publishPersistent(turn, "done", Map.of(), consumer);
        }
    }

    public Map<String, Object> replayEvent(SessionEventRecord event) {
        try {
            Map<String, Object> data = objectMapper.readValue(event.eventData(), new TypeReference<>() {});
            return event(event.seq(), event.eventType(), data);
        } catch (JsonProcessingException error) {
            return event(event.seq(), "error", Map.of("message", "stored event could not be decoded"));
        }
    }

    private void publishTransient(Turn turn, String type, Map<String, Object> data, EventConsumer consumer) {
        Map<String, Object> event = event(-1, type, data);
        consumer.accept(event);
        eventHub.broadcast(turn.agent().id(), turn.sessionId(), event);
    }

    private void publishPersistent(Turn turn, String type, Map<String, Object> data, EventConsumer consumer) {
        try {
            SessionEventRecord stored = store.appendEvent(
                    turn.userId(),
                    turn.agent().id(),
                    turn.sessionId(),
                    type,
                    objectMapper.writeValueAsString(data));
            Map<String, Object> event = event(stored.seq(), type, data);
            consumer.accept(event);
            eventHub.broadcast(turn.agent().id(), turn.sessionId(), event);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("could not persist chat event", error);
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

    public record Turn(
            String userId,
            AgentRecord agent,
            ProviderRecord provider,
            String sessionId,
            List<ChatMessageRecord> messages) {}

    @FunctionalInterface
    public interface EventConsumer {
        void accept(Map<String, Object> event);
    }
}
