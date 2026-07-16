package ai.openagent.bootstrap.api;

import ai.openagent.bootstrap.chat.ChatEventHub;
import ai.openagent.bootstrap.chat.ChatService;
import ai.openagent.bootstrap.persistence.ChatMessageRecord;
import ai.openagent.bootstrap.persistence.ChatSessionRecord;
import ai.openagent.bootstrap.persistence.OpenAgentStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
public class ChatController {

    private final OpenAgentStore store;
    private final ChatService chatService;
    private final ChatEventHub eventHub;
    private final ObjectMapper objectMapper;

    public ChatController(
            OpenAgentStore store,
            ChatService chatService,
            ChatEventHub eventHub,
            ObjectMapper objectMapper) {
        this.store = store;
        this.chatService = chatService;
        this.eventHub = eventHub;
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
    public SseEmitter subscribe(
            @RequestParam String agentId,
            @RequestParam String sessionId,
            @RequestParam(defaultValue = "-1") long since)
            throws IOException {
        SseEmitter emitter = eventHub.subscribe(agentId, sessionId);
        for (var event : store.listEventsSince(OpenAgentStore.LOCAL_USER_ID, agentId, sessionId, since)) {
            emitter.send(SseEmitter.event().data(chatService.replayEvent(event)));
        }
        return emitter;
    }

    @PostMapping(path = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> stream(@RequestBody ChatRequest request) {
        ChatService.Turn turn = chatService.beginTurn(request.agentId(), request.sessionId(), request.message());
        StreamingResponseBody body = output -> {
            SseWriter writer = new SseWriter(output, objectMapper);
            chatService.stream(turn, writer::send);
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

    private static final class SseWriter {
        private final OutputStream output;
        private final ObjectMapper objectMapper;

        private SseWriter(OutputStream output, ObjectMapper objectMapper) {
            this.output = output;
            this.objectMapper = objectMapper;
        }

        private synchronized void send(Map<String, Object> event) {
            try {
                String frame = "data: " + objectMapper.writeValueAsString(event) + "\n\n";
                output.write(frame.getBytes(StandardCharsets.UTF_8));
                output.flush();
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }
    }
}
