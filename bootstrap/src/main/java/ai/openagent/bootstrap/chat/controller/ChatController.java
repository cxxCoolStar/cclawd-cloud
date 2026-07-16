package ai.openagent.bootstrap.chat.controller;

import ai.openagent.bootstrap.chat.config.ChatProperties;
import ai.openagent.bootstrap.chat.controller.request.ChatStreamRequest;
import ai.openagent.bootstrap.chat.controller.vo.ChatHistoryVO;
import ai.openagent.bootstrap.chat.controller.vo.ChatSessionListVO;
import ai.openagent.bootstrap.chat.controller.vo.ChatTodoVO;
import ai.openagent.bootstrap.chat.event.ChatEventHub;
import ai.openagent.bootstrap.chat.service.ChatService;
import ai.openagent.bootstrap.chat.service.ChatTurnCoordinator;
import ai.openagent.framework.web.SseStreamWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 聊天控制器
 * 提供聊天回合发起（SSE 流式）、事件订阅回放、历史消息与会话查询接口
 *
 * <p>
 * SSE 协议对齐 fastclaw：事件帧带 {@code id: <seq>} 行 + JSON 内嵌 seq；
 * 空闲心跳 {@code : ping}；subscribe 端点支持 {@code Last-Event-ID} 头
 * （浏览器 EventSource 自动重连）与 {@code ?since=N} 参数双游标恢复
 * </p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final ChatEventHub eventHub;
    private final ChatTurnCoordinator turnCoordinator;
    private final ChatProperties chatProperties;
    private final ObjectMapper objectMapper;

    /**
     * 查询会话历史消息与事件 resume 游标
     */
    @GetMapping("/api/chat/history")
    public ChatHistoryVO history(@RequestParam String agentId, @RequestParam String sessionId) {
        return chatService.history(agentId, sessionId);
    }

    /**
     * 查询 agent 下的会话列表
     */
    @GetMapping("/api/chat/sessions")
    public ChatSessionListVO sessions(@RequestParam String agentId) {
        return chatService.sessions(agentId);
    }

    /**
     * 查询会话待办清单（agent 工作区能力未落地前恒为空，前端自动隐藏面板）
     */
    @GetMapping("/api/chat/todo")
    public ChatTodoVO todo() {
        return ChatTodoVO.empty();
    }

    /**
     * 订阅会话事件流（SSE 长连接）
     *
     * <p>
     * 恢复游标优先级：{@code Last-Event-ID} 头（浏览器重连自动携带）优先，
     * 其次 {@code ?since=N}；-1 表示只收实时事件不回放。回放持久化事件后
     * 转发实时事件，其中丢弃两类：seq 不大于已回放游标的重复事件（防止
     * 重连竞态双重渲染）、content_delta 高频瞬时事件（发起回合的
     * /api/chat/stream 连接已在渲染，此处转发会造成同屏双打；中途加入者
     * 靠随后的完整 content 事件补齐）
     * </p>
     */
    @GetMapping(path = "/api/chat/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public StreamingResponseBody subscribe(
            @RequestParam String agentId,
            @RequestParam String sessionId,
            @RequestParam(defaultValue = "-1") long since,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        long initialCursor = resolveCursor(lastEventId, since);
        ChatEventHub.Subscription subscription = eventHub.subscribe(agentId, sessionId);
        return output -> {
            try (subscription) {
                SseStreamWriter writer = new SseStreamWriter(output, objectMapper);
                // 立即 flush 一帧注释，让客户端 EventSource 尽快触发 open
                writer.comment("ok");

                // 回放持久化事件，游标推进到已回放的最大 seq
                long cursor = initialCursor;
                for (Map<String, Object> replayed : chatService.replayEventsSince(agentId, sessionId, cursor)) {
                    long seq = sequence(replayed);
                    writer.send(seq, replayed);
                    cursor = Math.max(cursor, seq);
                }

                while (true) {
                    Map<String, Object> event = subscription.poll(chatProperties.heartbeatInterval());
                    if (event == null) {
                        writer.comment("ping");
                        continue;
                    }
                    if ("content_delta".equals(event.get("type"))) {
                        continue;
                    }
                    long seq = sequence(event);
                    if (seq >= 0 && seq <= cursor) {
                        continue;
                    }
                    if (seq >= 0) {
                        cursor = seq;
                    }
                    writer.send(seq, event);
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } catch (IOException error) {
                // 浏览器断开只结束本订阅，回合本体不受影响
                log.debug("[sse] subscribe 客户端断开，agentId={}, sessionId={}", agentId, sessionId);
            }
        };
    }

    /**
     * 发起聊天回合并以 SSE 返回本回合事件流
     *
     * <p>
     * 回合由协调器在独立线程池执行——客户端断开后模型调用照常完成并落库，
     * 页面刷新可经 /api/chat/subscribe?since=N 补收剩余事件
     * </p>
     */
    @PostMapping(path = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> stream(@RequestBody @Valid ChatStreamRequest requestParam) {
        ChatTurnCoordinator.TurnStream turn =
                turnCoordinator.start(requestParam.agentId(), requestParam.sessionId(), requestParam.message());
        StreamingResponseBody body = output -> {
            ChatEventHub.Subscription subscription = turn.subscription();
            try (subscription) {
                SseStreamWriter writer = new SseStreamWriter(output, objectMapper);
                long lastActivityAt = System.currentTimeMillis();
                while (true) {
                    Map<String, Object> event = subscription.poll(chatProperties.streamPollInterval());
                    if (event != null) {
                        writer.send(sequence(event), event);
                        if ("done".equals(event.get("type"))) {
                            return;
                        }
                        lastActivityAt = System.currentTimeMillis();
                    } else if (turn.completion().isDone()) {
                        return;
                    } else if (System.currentTimeMillis() - lastActivityAt
                            >= chatProperties.heartbeatInterval().toMillis()) {
                        // 模型长时间思考未产出内容时保活，防止代理掐断空闲连接
                        writer.comment("ping");
                        lastActivityAt = System.currentTimeMillis();
                    }
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } catch (IOException error) {
                // 回合归协调器所有，客户端断开后继续在后台执行
                log.debug("[sse] stream 客户端断开，agentId={}, sessionId={}",
                        requestParam.agentId(), requestParam.sessionId());
            }
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Accel-Buffering", "no")
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(body);
    }

    /**
     * 恢复游标：Last-Event-ID 头（浏览器管理的重连）优先于 since 参数
     */
    private static long resolveCursor(String lastEventId, long since) {
        if (lastEventId != null && !lastEventId.isBlank()) {
            try {
                return Long.parseLong(lastEventId.trim());
            } catch (NumberFormatException ignored) {
                // 非法头按未携带处理，回退 since
            }
        }
        return since;
    }

    private static long sequence(Map<String, Object> event) {
        Object value = event.get("seq");
        return value instanceof Number number ? number.longValue() : -1;
    }
}
