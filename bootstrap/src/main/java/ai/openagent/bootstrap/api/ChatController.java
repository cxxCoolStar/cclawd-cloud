package ai.openagent.bootstrap.api;

import ai.openagent.bootstrap.chat.ChatEventHub;
import ai.openagent.bootstrap.chat.ChatService;
import ai.openagent.bootstrap.chat.ChatTurnCoordinator;
import ai.openagent.bootstrap.persistence.ChatMessageRecord;
import ai.openagent.bootstrap.persistence.ChatSessionRecord;
import ai.openagent.bootstrap.persistence.OpenAgentStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
public class ChatController {

    private final OpenAgentStore store;
    private final ChatService chatService;
    private final ChatEventHub eventHub;
    private final ChatTurnCoordinator turnCoordinator;
    private final ObjectMapper objectMapper;

    public ChatController(
            OpenAgentStore store,
            ChatService chatService,
            ChatEventHub eventHub,
            ChatTurnCoordinator turnCoordinator,
            ObjectMapper objectMapper) {
        this.store = store;
        this.chatService = chatService;
        this.eventHub = eventHub;
        this.turnCoordinator = turnCoordinator;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/api/chat/history")
    public Map<String, Object> history(@RequestParam String agentId, @RequestParam String sessionId) {
        List<Map<String, Object>> history = store.listMessages(OpenAgentStore.LOCAL_USER_ID, agentId, sessionId)
                .stream()
                .map(this::historyMessage)
                .toList();
        return Map.of(
                "history", history,
                "latestEventSeq",
                store.latestEventSequence(OpenAgentStore.LOCAL_USER_ID, agentId, sessionId));
    }

    @GetMapping("/api/chat/sessions")
    public Map<String, Object> sessions(@RequestParam String agentId) {
        List<Map<String, Object>> sessions = store.listSessions(OpenAgentStore.LOCAL_USER_ID, agentId).stream()
                .map(this::sessionResponse)
                .toList();
        return Map.of("sessions", sessions);
    }

    @GetMapping("/api/chat/todo")
    public Map<String, Object> todo() {
        return Map.of("items", List.of(), "raw", "");
    }

    @GetMapping(path = "/api/chat/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public StreamingResponseBody subscribe(
            @RequestParam String agentId,
            @RequestParam String sessionId,
            @RequestParam(defaultValue = "-1") long since) {
        ChatEventHub.Subscription subscription = eventHub.subscribe(agentId, sessionId);
        return output -> {
            try (subscription) {
                SseWriter writer = new SseWriter(output, objectMapper);
                Set<Long> replayedSequences = new HashSet<>();
                for (var stored : store.listEventsSince(OpenAgentStore.LOCAL_USER_ID, agentId, sessionId, since)) {
                    writer.send(chatService.replayEvent(stored));
                    replayedSequences.add(stored.seq());
                }
                while (true) {
                    Map<String, Object> event = subscription.poll(Duration.ofSeconds(15));
                    if (event == null) {
                        writer.heartbeat();
                        continue;
                    }
                    long seq = sequence(event);
                    if (seq < 0 || replayedSequences.add(seq)) {
                        writer.send(event);
                    }
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // Disconnecting a browser only closes this subscription.
            }
        };
    }

    @PostMapping(path = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> stream(@RequestBody ChatRequest request) {
        ChatTurnCoordinator.TurnStream turn =
                turnCoordinator.start(request.agentId(), request.sessionId(), request.message());
        StreamingResponseBody body = output -> {
            ChatEventHub.Subscription subscription = turn.subscription();
            try (subscription) {
                SseWriter writer = new SseWriter(output, objectMapper);
                while (true) {
                    Map<String, Object> event = subscription.poll(Duration.ofSeconds(1));
                    if (event != null) {
                        writer.send(event);
                        if ("done".equals(event.get("type"))) {
                            return;
                        }
                    } else if (turn.completion().isDone()) {
                        return;
                    }
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // The coordinator owns the model turn, so it continues in the background.
            }
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Accel-Buffering", "no")
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(body);
    }

    private Map<String, Object> historyMessage(ChatMessageRecord message) {
        return Map.of("role", message.role(), "content", message.content());
    }

    private Map<String, Object> sessionResponse(ChatSessionRecord session) {
        return Map.of(
                "id", session.id(),
                "title", session.title(),
                "preview", session.preview(),
                "channel", session.channel(),
                "createdAt", session.createdAt(),
                "updatedAt", session.updatedAt());
    }

    public record ChatRequest(String agentId, String sessionId, String message) {}

    private static long sequence(Map<String, Object> event) {
        Object value = event.get("seq");
        return value instanceof Number number ? number.longValue() : -1;
    }

    private static final class SseWriter {
        private final OutputStream output;
        private final ObjectMapper objectMapper;

        private SseWriter(OutputStream output, ObjectMapper objectMapper) {
            this.output = output;
            this.objectMapper = objectMapper;
        }

        private synchronized void send(Map<String, Object> event) throws IOException {
            String frame = "data: " + objectMapper.writeValueAsString(event) + "\n\n";
            output.write(frame.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }

        private synchronized void heartbeat() throws IOException {
            output.write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }
}
