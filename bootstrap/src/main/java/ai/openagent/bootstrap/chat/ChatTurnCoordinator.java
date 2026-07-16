package ai.openagent.bootstrap.chat;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ChatTurnCoordinator {

    private final ChatService chatService;
    private final ChatEventHub eventHub;
    private final Executor executor;
    private final Set<String> activeTurns = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public ChatTurnCoordinator(
            ChatService chatService,
            ChatEventHub eventHub,
            @Qualifier("chatTurnExecutor") Executor executor) {
        this.chatService = chatService;
        this.eventHub = eventHub;
        this.executor = executor;
    }

    public TurnStream start(String agentId, String sessionId, String message) {
        String key = key(agentId, sessionId);
        if (!activeTurns.add(key)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "a chat turn is already running for this session");
        }

        ChatEventHub.Subscription subscription = eventHub.subscribe(agentId, sessionId);
        try {
            ChatService.Turn turn = chatService.beginTurn(agentId, sessionId, message);
            CompletableFuture<Void> completion = CompletableFuture.runAsync(() -> chatService.stream(turn), executor)
                    .whenComplete((ignored, error) -> activeTurns.remove(key));
            return new TurnStream(subscription, completion);
        } catch (RejectedExecutionException error) {
            subscription.close();
            activeTurns.remove(key);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "chat executor is busy", error);
        } catch (RuntimeException error) {
            subscription.close();
            activeTurns.remove(key);
            throw error;
        }
    }

    private static String key(String agentId, String sessionId) {
        return agentId + "\n" + sessionId;
    }

    public record TurnStream(ChatEventHub.Subscription subscription, CompletableFuture<Void> completion) {}
}