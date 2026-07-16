package ai.openagent.bootstrap.chat.service;

import ai.openagent.bootstrap.chat.event.ChatEventHub;
import ai.openagent.bootstrap.chat.event.ChatSessionKey;
import ai.openagent.framework.errorcode.BaseErrorCode;
import ai.openagent.framework.exception.ClientException;
import ai.openagent.framework.exception.ServiceException;
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
 * 线程池中异步跑完，HTTP 请求线程只订阅事件转发——客户端断开后模型调用
 * 仍会完成并落库（对齐 fastclaw 的 detached-context 语义）
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
     * @return 回合流（事件订阅 + 完成信号）
     */
    public TurnStream start(String agentId, String sessionId, String message) {
        String key = new ChatSessionKey(agentId, sessionId).compact();
        if (!activeTurns.add(key)) {
            throw new ClientException(
                    "a chat turn is already running for this session", BaseErrorCode.RESOURCE_CONFLICT);
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
            log.warn("[chat] 回合线程池已满，拒绝新回合，agentId={}, sessionId={}", agentId, sessionId);
            throw new ServiceException(
                    "chat executor is busy", error, BaseErrorCode.SERVICE_UNAVAILABLE_ERROR);
        } catch (RuntimeException error) {
            subscription.close();
            activeTurns.remove(key);
            throw error;
        }
    }

    /**
     * 回合流：事件订阅 + 异步完成信号
     */
    public record TurnStream(ChatEventHub.Subscription subscription, CompletableFuture<Void> completion) {}
}
