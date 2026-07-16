package ai.openagent.bootstrap.chat.sse;

import ai.openagent.bootstrap.chat.config.ChatProperties;
import ai.openagent.bootstrap.chat.event.ChatEventHub;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 聊天 SSE 连接工厂
 *
 * <p>
 * 创建 {@link ChatSseStream} 并完成三件接线：向 {@code ChatEventHub}
 * 注册推模式订阅、登记到共享心跳调度器、连接关闭时自动反注册。
 * 全部连接共用一个单线程调度器定时发 {@code : ping}（对齐 fastclaw
 * 的 30s keepalive，防 nginx/Cloudflare/ELB 掐断空闲连接）——心跳
 * 同时兼任死连接探测：EventSource 关闭后底层 TCP 未必立刻可感知，
 * 下一次 ping 写失败即回收，不再有连接独占线程阻塞等待的问题
 * </p>
 */
@Slf4j
@Component
public class ChatSseStreamFactory {

    private final ChatEventHub eventHub;
    private final ObjectMapper objectMapper;
    private final Set<ChatSseStream> active = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService heartbeat;

    public ChatSseStreamFactory(ChatEventHub eventHub, ObjectMapper objectMapper, ChatProperties chatProperties) {
        this.eventHub = eventHub;
        this.objectMapper = objectMapper;
        long intervalMillis = chatProperties.heartbeatInterval().toMillis();
        this.heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "chat-sse-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        heartbeat.scheduleAtFixedRate(this::pingAll, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * 为 POST /api/chat/stream 创建回合流连接：转发 content_delta，
     * 收到 done 后自动结束，立即进入实时转发（无回放阶段）
     */
    public ChatSseStream openTurnStream() {
        return open(true, true, -1, true);
    }

    /**
     * 为 GET /api/chat/subscribe 创建订阅连接：丢弃 content_delta
     * （发起回合的 POST 流已在渲染，转发会同屏双打；中途加入者靠随后
     * 的完整 content 事件补齐），长连保持，回放完成后由调用方
     * {@link ChatSseStream#goLive} 放行实时事件
     *
     * @param initialCursor 恢复游标：seq 不大于该值的事件视为已送达
     */
    public ChatSseStream openSubscribeStream(long initialCursor) {
        return open(false, false, initialCursor, false);
    }

    private ChatSseStream open(boolean forwardDeltas, boolean completeOnDone, long initialCursor, boolean live) {
        ChatSseStream stream =
                new ChatSseStream(objectMapper, forwardDeltas, completeOnDone, initialCursor, live, active::remove);
        active.add(stream);
        return stream;
    }

    /**
     * 将连接接入指定会话的事件流（在回放开始前调用，保证回放期间落库
     * 的事件不丢——对齐 fastclaw subscribe-before-replay 语义）
     */
    public void connect(ChatSseStream stream, String agentId, String sessionId) {
        stream.attach(eventHub.subscribe(agentId, sessionId, stream::onEvent));
    }

    private void pingAll() {
        for (ChatSseStream stream : active) {
            stream.comment("ping");
        }
    }

    @PreDestroy
    void shutdown() {
        heartbeat.shutdownNow();
        for (ChatSseStream stream : active) {
            stream.close();
        }
    }
}
