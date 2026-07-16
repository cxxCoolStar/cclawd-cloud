package ai.openagent.bootstrap.chat.event;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * 聊天事件中心
 *
 * <p>
 * 以 (agentId, sessionId) 为维度的进程内发布/订阅：聊天回合执行线程
 * 通过 {@link #broadcast} 推送事件，各 SSE 连接通过 {@link #subscribe}
 * 建立带缓冲的订阅。缓冲满时的背压策略：瞬时增量事件（content_delta）
 * 直接丢弃（订阅方可通过后续的完整 content 事件补齐），持久事件挤掉
 * 队头旧事件以保证送达
 * </p>
 */
@Component
public class ChatEventHub {

    private static final int SUBSCRIBER_BUFFER_SIZE = 256;

    private final Map<String, Set<Subscription>> subscriptions = new ConcurrentHashMap<>();

    /**
     * 订阅指定会话的事件流
     */
    public Subscription subscribe(String agentId, String sessionId) {
        String key = new ChatSessionKey(agentId, sessionId).compact();
        Subscription subscription = new Subscription(key);
        subscriptions.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet()).add(subscription);
        return subscription;
    }

    /**
     * 向指定会话的所有订阅者广播事件
     */
    public void broadcast(String agentId, String sessionId, Map<String, Object> event) {
        String key = new ChatSessionKey(agentId, sessionId).compact();
        for (Subscription subscription : subscriptions.getOrDefault(key, Set.of())) {
            subscription.offer(event);
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
     * 单个订阅：带缓冲的事件队列，关闭后自动从事件中心注销
     */
    public final class Subscription implements AutoCloseable {
        private final String key;
        private final BlockingQueue<Map<String, Object>> events =
                new ArrayBlockingQueue<>(SUBSCRIBER_BUFFER_SIZE);
        private final AtomicBoolean closed = new AtomicBoolean();

        private Subscription(String key) {
            this.key = key;
        }

        /**
         * 拉取下一条事件，超时返回 {@code null}
         */
        public Map<String, Object> poll(Duration timeout) throws InterruptedException {
            return events.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        private void offer(Map<String, Object> event) {
            if (closed.get() || events.offer(event)) {
                return;
            }
            // 缓冲已满：content_delta 属于高频瞬时事件，直接丢弃；
            // 其余事件挤掉队头旧事件保证送达
            if ("content_delta".equals(event.get("type"))) {
                return;
            }
            while (!closed.get() && !events.offer(event)) {
                events.poll();
            }
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                remove(this);
                events.clear();
            }
        }
    }
}
