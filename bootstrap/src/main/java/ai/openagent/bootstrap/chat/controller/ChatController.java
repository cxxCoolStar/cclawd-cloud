package ai.openagent.bootstrap.chat.controller;

import ai.openagent.bootstrap.agentrun.AgentRunCoordinator;
import ai.openagent.bootstrap.agent.service.AgentService;
import ai.openagent.bootstrap.chat.controller.request.ChatStreamRequest;
import ai.openagent.bootstrap.chat.controller.vo.ChatHistoryVO;
import ai.openagent.bootstrap.chat.controller.vo.ChatSessionListVO;
import ai.openagent.bootstrap.chat.controller.vo.ChatTodoVO;
import ai.openagent.bootstrap.chat.service.ChatService;
import ai.openagent.bootstrap.chat.sse.ChatSseStream;
import ai.openagent.bootstrap.chat.sse.ChatSseStreamFactory;
import jakarta.validation.Valid;
import java.util.List;
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
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

/**
 * 聊天控制器
 * 提供聊天回合发起（SSE 流式）、事件订阅回放、历史消息与会话查询接口
 *
 * <p>
 * SSE 协议对齐 fastclaw：事件帧带 {@code id: <seq>} 行 + JSON 内嵌 seq；
 * 空闲心跳 {@code : ping}；subscribe 端点支持 {@code Last-Event-ID} 头
 * （浏览器 EventSource 自动重连）与 {@code ?since=N} 参数双游标恢复。
 * 两个 SSE 端点均为推模式（事件中心回调直写 emitter）——连接不占用
 * 线程池线程，客户端断开由写失败即时回收，会话快速切换堆积的死连接
 * 不会拖垮 MVC 异步线程池（对齐 fastclaw goroutine-per-connection 语义）
 * </p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final AgentRunCoordinator runCoordinator;
    private final ChatSseStreamFactory sseStreamFactory;
    private final AgentService agentService;

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
     * 其次 {@code ?since=N}；-1 表示回放全部持久化事件。时序：先订阅事件
     * 中心（实时事件暂存 backlog）→ 回放持久化事件 → goLive 按 seq 去重
     * 刷出 backlog——保证回放期间落库的事件不重不漏（对齐 fastclaw
     * subscribe-before-replay）。content_delta 在本端点丢弃：发起回合的
     * /api/chat/stream 连接已在渲染，转发会同屏双打；中途加入者靠随后的
     * 完整 content 事件补齐
     * </p>
     */
    @GetMapping(path = "/api/chat/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<ResponseBodyEmitter> subscribe(
            @RequestParam String agentId,
            @RequestParam String sessionId,
            @RequestParam(defaultValue = "-1") long since,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        long cursor = resolveCursor(lastEventId, since);
        ChatSseStream stream = sseStreamFactory.openSubscribeStream(cursor);
        try {
            sseStreamFactory.connect(stream, agentId, sessionId);
            // 立即发一帧注释，让客户端 EventSource 尽快触发 open
            stream.comment("ok");
            List<Map<String, Object>> replayed = chatService.replayEventsSince(agentId, sessionId, cursor);
            stream.replay(replayed);
            stream.goLive();
        } catch (RuntimeException error) {
            stream.close();
            throw error;
        }
        return sse(stream);
    }

    /**
     * 发起聊天回合并以 SSE 返回本回合事件流
     *
     * <p>
     * 回合作为一次 Agent 运行由协调器在独立线程池执行（V2 起统一走
     * AgentKernel，无工具聊天是工具列表为空的退化情形）——客户端断开后
     * 运行照常完成并落库，页面刷新可经 /api/chat/subscribe?since=N 补收
     * 剩余事件。连接在订阅建立后才开启运行，保证首个事件不丢；收到
     * done 事件自动结束
     * </p>
     */
    @PostMapping(path = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<ResponseBodyEmitter> stream(@RequestBody @Valid ChatStreamRequest requestParam) {
        // 归属/scope 校验在开启流之前完成：越权走全局异常处理器返回 JSON 404/403
        agentService.requireAccess(requestParam.agentId());
        ChatSseStream stream = sseStreamFactory.openTurnStream();
        try {
            sseStreamFactory.connect(stream, requestParam.agentId(), requestParam.sessionId());
            runCoordinator.start(requestParam.agentId(), requestParam.sessionId(), requestParam.message());
        } catch (RuntimeException error) {
            // 回合未能入队（参数校验失败等）——释放连接，交给全局异常处理器
            // 输出 JSON 错误响应（同会话并发自 V8 起排队，不再 409）
            stream.close();
            throw error;
        }
        return sse(stream);
    }

    private static ResponseEntity<ResponseBodyEmitter> sse(ChatSseStream stream) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Accel-Buffering", "no")
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(stream.emitter());
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
}
