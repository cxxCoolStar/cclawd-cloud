package ai.openagent.bootstrap.chat.event;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 聊天事件中心
 *
 * <p>
 * 以 (agentId, sessionId) 为维度的进程内发布/订阅：聊天回合执行线程
 * 通过 {@link #broadcast} 推送事件，事件直接在发布线程上回调各订阅者
 * 的 listener（推模式）。SSE 连接侧由 {@code ChatSseStream} 承接回调并
 * 写出 SseEmitter，连接不再占用任何轮询线程：每个连接不消耗额外线程，
 * 客户端断开由写失败即时感知并注销
 * </p>
 *
 * <p>
 * 背压说明：listener 回调发生在发布线程上，慢消费者的网络写会短暂
 * 拖慢回合线程；listener 抛出的任何异常都视为订阅已死，就地注销，
 * 保证单个坏连接不影响回合本体与其他订阅者（防御目标：慢消费者被跳过而非阻塞）
 * </p>
 */
@Slf4j
@Component
public class ChatEventHub {

    private final Map<String, Set<Subscription>> subscriptions = new ConcurrentHashMap<>();

    /**
     * 订阅指定会话的事件流
     *
     * @param listener 事件回调；不得阻塞发布线程，异常视为订阅失效
     */
    public Subscription subscribe(String agentId, String sessionId, Consumer<Map<String, Object>> listener) {
        String key = new ChatSessionKey(agentId, sessionId).compact();
        Subscription subscription = new Subscription(key, listener);
        subscriptions.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet()).add(subscription);
        return subscription;
    }

    /**
     * 向指定会话的所有订阅者广播事件；回调异常的订阅者被就地注销
     */
    public void broadcast(String agentId, String sessionId, Map<String, Object> event) {
        String key = new ChatSessionKey(agentId, sessionId).compact();
        for (Subscription subscription : subscriptions.getOrDefault(key, Set.of())) {
            subscription.dispatch(event);
        }
    }

    private void remove(Subscription subscription) {
        Set<Subscription> subscribers = subscriptions.get(subscription.key);
        if (subscribers == null) {
            return;
        }
        subscribers.remove(subscription);
        if (subscribers.isEmpty()) {
            subscriptions.remove(subscription.key, subscribers);
        }
    }

    /**
     * 单个订阅：持有事件回调，关闭后自动从事件中心注销（幂等）
     */
    public final class Subscription implements AutoCloseable {
        private final String key;
        private final Consumer<Map<String, Object>> listener;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Subscription(String key, Consumer<Map<String, Object>> listener) {
            this.key = key;
            this.listener = listener;
        }

        private void dispatch(Map<String, Object> event) {
            if (closed.get()) {
                return;
            }
            try {
                listener.accept(event);
            } catch (RuntimeException error) {
                // 订阅者回调失败（连接已死等）——注销自身，不影响回合线程
                log.debug("[chat] 事件订阅者回调失败，注销该订阅，key={}", key, error);
                close();
            }
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                remove(this);
            }
        }
    }
}
