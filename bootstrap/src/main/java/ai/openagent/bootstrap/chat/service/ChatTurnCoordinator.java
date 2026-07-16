package ai.openagent.bootstrap.chat.service;

import ai.openagent.bootstrap.chat.event.ChatEventHub;
import ai.openagent.bootstrap.chat.event.ChatSessionKey;
import ai.openagent.framework.errorcode.BaseErrorCode;
import ai.openagent.framework.exception.ClientException;
import ai.openagent.framework.exception.ServiceException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 聊天回合协调器
 *
 * <p>
 * 以 (agentId, sessionId) 为粒度保证同一会话同时只有一个回合在执行
 * （并发开启回合抛 {@code RESOURCE_CONFLICT} → HTTP 409）。回合在独立
 * 线程池中异步跑完，事件经 {@code ChatEventHub} 推给各 SSE 连接——
 * HTTP 请求线程不参与转发，客户端断开后模型调用仍会完成并落库
 * （对齐 fastclaw 的 detached-context 语义）
 * </p>
 */
@Slf4j
@Component
public class ChatTurnCoordinator {

    private final ChatService chatService;
    private final ChatEventHub eventHub;
    private final Executor executor;
    private final Set<String> activeTurns = ConcurrentHashMap.newKeySet();

    public ChatTurnCoordinator(
            ChatService chatService,
            ChatEventHub eventHub,
            @Qualifier("chatTurnExecutor") Executor executor) {
        this.chatService = chatService;
        this.eventHub = eventHub;
        this.executor = executor;
    }

    /**
     * 开启一个异步聊天回合
     *
     * <p>
     * 兜底保证回合必然以 done 事件收敛：{@code ChatService#stream} 正常
     * 路径已发布 content/done（异常路径 error/done），但若持久化本身
     * 失败导致 stream 异常逃逸，这里补发瞬时（seq=-1）error/done 事件，
     * 防止 SSE 连接悬空等待（对齐 fastclaw handleChatStream 的
     * "agent finished without emitting a response" 安全网）
     * </p>
     *
     * @return 回合完成信号（调用方通常不需要等待——事件流才是主通道）
     */
    public CompletableFuture<Void> start(String agentId, String sessionId, String message) {
        String key = new ChatSessionKey(agentId, sessionId).compact();
        if (!activeTurns.add(key)) {
            throw new ClientException(
                    "a chat turn is already running for this session", BaseErrorCode.RESOURCE_CONFLICT);
        }

        try {
            ChatService.Turn turn = chatService.beginTurn(agentId, sessionId, message);
            return CompletableFuture.runAsync(() -> chatService.stream(turn), executor)
                    .whenComplete((ignored, error) -> {
                        activeTurns.remove(key);
                        if (error != null) {
                            log.error("[chat] 回合异常逃逸，补发收敛事件，agentId={}, sessionId={}",
                                    agentId, sessionId, error);
                            eventHub.broadcast(agentId, sessionId, transientEvent(
                                    "error", Map.of("message", "chat turn failed unexpectedly")));
                            eventHub.broadcast(agentId, sessionId, transientEvent("done", Map.of()));
                        }
                    });
        } catch (RejectedExecutionException error) {
            activeTurns.remove(key);
            log.warn("[chat] 回合线程池已满，拒绝新回合，agentId={}, sessionId={}", agentId, sessionId);
            throw new ServiceException(
                    "chat executor is busy", error, BaseErrorCode.SERVICE_UNAVAILABLE_ERROR);
        } catch (RuntimeException error) {
            activeTurns.remove(key);
            throw error;
        }
    }

    private static Map<String, Object> transientEvent(String type, Map<String, Object> data) {
        return Map.of("seq", -1L, "type", type, "data", data);
    }
}
