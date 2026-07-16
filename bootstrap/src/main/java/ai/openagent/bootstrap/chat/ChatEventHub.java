package ai.openagent.bootstrap.chat;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

@Component
public class ChatEventHub {

    private static final int SUBSCRIBER_BUFFER_SIZE = 256;

    private final Map<String, Set<Subscription>> subscriptions = new ConcurrentHashMap<>();

    public Subscription subscribe(String agentId, String sessionId) {
        String key = key(agentId, sessionId);
        Subscription subscription = new Subscription(key);
        subscriptions.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet()).add(subscription);
        return subscription;
    }

    public void broadcast(String agentId, String sessionId, Map<String, Object> event) {
        for (Subscription subscription : subscriptions.getOrDefault(key(agentId, sessionId), Set.of())) {
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

    private static String key(String agentId, String sessionId) {
        return agentId + "\n" + sessionId;
    }

    public final class Subscription implements AutoCloseable {
        private final String key;
        private final BlockingQueue<Map<String, Object>> events =
                new ArrayBlockingQueue<>(SUBSCRIBER_BUFFER_SIZE);
        private final AtomicBoolean closed = new AtomicBoolean();

        private Subscription(String key) {
            this.key = key;
        }

        public Map<String, Object> poll(Duration timeout) throws InterruptedException {
            return events.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        private void offer(Map<String, Object> event) {
            if (closed.get() || events.offer(event)) {
                return;
            }
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