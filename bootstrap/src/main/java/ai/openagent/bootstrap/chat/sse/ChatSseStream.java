package ai.openagent.bootstrap.chat.sse;

import ai.openagent.bootstrap.chat.event.ChatEventHub;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

/**
 * 单条聊天 SSE 连接
 *
 * <p>
 * 封装一个无超时的 {@link ResponseBodyEmitter}：事件由 {@code ChatEventHub}
 * 在发布线程上经 {@link #onEvent} 推入，本类负责协议编码与连接生命周期，
 * 连接本身不占用任何轮询线程。协议对齐 fastclaw：帧格式为
 * {@code id: <seq>} 行 + {@code data: <json>}（冒号后带空格——前端 POST
 * 流的手写解析器按 {@code "data: "} 前缀匹配，故不使用 Spring SseEmitter
 * 的自动编帧）；帧以 UTF-8 字节直写，绕过字符串转换器的默认字符集
 * </p>
 *
 * <p>
 * 回放/实时竞态用闸门解决：{@code live=false} 期间（subscribe 端点回放
 * 持久化事件时）实时事件先进 backlog，{@link #goLive} 后按 seq 去重刷出，
 * 保证「回放中落库的事件要么在回放范围内、要么在 backlog 里，不重不漏」
 * （对齐 fastclaw handleChatSubscribe 的 subscribe-before-replay 语义）。
 * 任何写失败即视为客户端已断开：立即注销 hub 订阅并结束连接——这是
 * 方案的核心，死连接不再滞留等待下一次心跳超时
 * </p>
 */
@Slf4j
public final class ChatSseStream {

    private final ResponseBodyEmitter emitter = new ResponseBodyEmitter(0L);
    private final ObjectMapper objectMapper;

    /** 是否转发 content_delta（发起回合的 POST 流转发；subscribe 丢弃，防同屏双打） */
    private final boolean forwardDeltas;

    /** 收到 done 事件后是否结束连接（POST 流一回合即止；subscribe 长连保持） */
    private final boolean completeOnDone;

    /** 关闭时回调（工厂用于从心跳注册表移除） */
    private final Consumer<ChatSseStream> onClose;

    private final Object lock = new Object();
    private final List<Map<String, Object>> backlog = new ArrayList<>();
    private ChatEventHub.Subscription subscription;
    private long cursor;
    private boolean live;
    private boolean closed;

    ChatSseStream(
            ObjectMapper objectMapper,
            boolean forwardDeltas,
            boolean completeOnDone,
            long initialCursor,
            boolean liveImmediately,
            Consumer<ChatSseStream> onClose) {
        this.objectMapper = objectMapper;
        this.forwardDeltas = forwardDeltas;
        this.completeOnDone = completeOnDone;
        this.cursor = initialCursor;
        this.live = liveImmediately;
        this.onClose = onClose;
        emitter.onCompletion(this::close);
        emitter.onError(error -> close());
        emitter.onTimeout(this::close);
    }

    public ResponseBodyEmitter emitter() {
        return emitter;
    }

    /**
     * 绑定事件中心订阅；若连接在绑定前已死则立即注销订阅
     */
    void attach(ChatEventHub.Subscription subscription) {
        synchronized (lock) {
            this.subscription = subscription;
            if (closed) {
                subscription.close();
            }
        }
    }

    /**
     * 事件中心回调入口：closed 丢弃、按需过滤 content_delta、
     * 未 goLive 时暂存 backlog，否则直接下发
     */
    void onEvent(Map<String, Object> event) {
        synchronized (lock) {
            if (closed) {
                return;
            }
            if (!forwardDeltas && "content_delta".equals(event.get("type"))) {
                return;
            }
            if (!live) {
                backlog.add(event);
                return;
            }
            dispatch(event);
        }
    }

    /**
     * 下发一批回放事件（subscribe 端点在 goLive 前调用）
     */
    public void replay(List<Map<String, Object>> events) {
        synchronized (lock) {
            for (Map<String, Object> event : events) {
                if (closed) {
                    return;
                }
                dispatch(event);
            }
        }
    }

    /**
     * 结束回放阶段：按 seq 去重刷出暂存的实时事件，此后事件直接下发
     */
    public void goLive() {
        synchronized (lock) {
            for (Map<String, Object> event : backlog) {
                if (closed) {
                    break;
                }
                dispatch(event);
            }
            backlog.clear();
            live = true;
        }
    }

    /**
     * 发送 SSE 注释帧（{@code : ok} 连接确认 / {@code : ping} 保活心跳）；
     * 写失败即结束连接
     */
    public void comment(String text) {
        synchronized (lock) {
            if (closed) {
                return;
            }
            try {
                sendRaw(": " + text + "\n\n");
            } catch (Exception error) {
                closeLocked();
            }
        }
    }

    /**
     * 结束连接：注销 hub 订阅、从心跳注册表移除、完成 emitter（幂等）
     */
    public void close() {
        synchronized (lock) {
            closeLocked();
        }
    }

    /**
     * 编码并写出一帧事件，seq 不大于游标的重复事件丢弃；
     * 写失败关闭连接，completeOnDone 时遇 done 事件正常收尾
     */
    private void dispatch(Map<String, Object> event) {
        long seq = sequence(event);
        if (seq >= 0 && seq <= cursor) {
            return;
        }
        if (seq >= 0) {
            cursor = seq;
        }
        try {
            StringBuilder frame = new StringBuilder();
            if (seq >= 0) {
                frame.append("id: ").append(seq).append('\n');
            }
            frame.append("data: ").append(objectMapper.writeValueAsString(event)).append("\n\n");
            sendRaw(frame.toString());
        } catch (Exception error) {
            closeLocked();
            return;
        }
        if (completeOnDone && "done".equals(event.get("type"))) {
            closeLocked();
        }
    }

    /**
     * 以 UTF-8 字节直写：byte[] 走 ByteArrayHttpMessageConverter 原样输出，
     * 避免 String 转换器按 ISO-8859-1 编码弄坏中文
     */
    private void sendRaw(String frame) throws IOException {
        emitter.send(frame.getBytes(StandardCharsets.UTF_8), MediaType.APPLICATION_OCTET_STREAM);
    }

    private void closeLocked() {
        if (closed) {
            return;
        }
        closed = true;
        backlog.clear();
        if (subscription != null) {
            subscription.close();
        }
        onClose.accept(this);
        // 若 emitter 已因客户端断开进入错误态，complete() 会抛异常
        // 用 try-catch 兜住所有情况（IllegalStateException / AsyncRequestNotUsableException 等）
        try {
            emitter.complete();
        } catch (IllegalStateException | IllegalArgumentException e) {
            // 连接已死，无需处理
            log.debug("SSE emitter complete skipped: {}", e.getMessage());
        } catch (Exception e) {
            // 其他异常也忽略
            log.debug("SSE emitter complete error: {}", e.getClass().getSimpleName());
        }
    }

    private static long sequence(Map<String, Object> event) {
        Object value = event.get("seq");
        return value instanceof Number number ? number.longValue() : -1;
    }
}
