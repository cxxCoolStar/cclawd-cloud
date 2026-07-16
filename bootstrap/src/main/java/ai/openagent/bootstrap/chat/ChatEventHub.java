package ai.openagent.bootstrap.chat;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class ChatEventHub {

    private final Map<String, Set<SseEmitter>> subscriptions = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String agentId, String sessionId) {
        String key = key(agentId, sessionId);
        SseEmitter emitter = new SseEmitter(0L);
        subscriptions.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet()).add(emitter);
        Runnable remove = () -> remove(key, emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(error -> remove.run());
        return emitter;
    }

    public void broadcast(String agentId, String sessionId, Map<String, Object> event) {
        String key = key(agentId, sessionId);
        Set<SseEmitter> emitters = subscriptions.getOrDefault(key, Set.of());
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().data(event));
            } catch (IOException | IllegalStateException error) {
                remove(key, emitter);
            }
        }
    }

    private void remove(String key, SseEmitter emitter) {
        Set<SseEmitter> emitters = subscriptions.get(key);
        if (emitters == null) {
            return;
        }
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            subscriptions.remove(key, emitters);
        }
    }

    private static String key(String agentId, String sessionId) {
        return agentId + "\n" + sessionId;
    }
}
