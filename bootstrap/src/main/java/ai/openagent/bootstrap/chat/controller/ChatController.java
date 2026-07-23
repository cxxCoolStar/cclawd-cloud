package ai.openagent.bootstrap.chat.controller;

import ai.openagent.bootstrap.chat.controller.request.ChatStreamRequest;
import ai.openagent.bootstrap.chat.controller.vo.ChatHistoryVO;
import ai.openagent.bootstrap.chat.controller.vo.ChatSessionListVO;
import ai.openagent.bootstrap.chat.controller.vo.ChatTodoVO;
import ai.openagent.bootstrap.chat.service.ChatService;
import ai.openagent.bootstrap.chat.service.ChatStreamService;
import ai.openagent.bootstrap.chat.sse.ChatSseStream;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
 * SSE 协议实现：事件帧带 {@code id: <seq>} 行 + JSON 内嵌 seq；
 * 空闲心跳 {@code : ping}；subscribe 端点支持 {@code Last-Event-ID} 头
 * （浏览器 EventSource 自动重连）与 {@code ?since=N} 参数双游标恢复。
 * 两个 SSE 端点均为推模式（事件中心回调直写 emitter）——连接不占用
 * 线程池线程，客户端断开由写失败即时回收，会话快速切换堆积的死连接
 * 不会拖垮 MVC 异步线程池（采用事件驱动的推送模式，实现类似 goroutine-per-connection 的轻量级并发语义）
 * </p>
 */
@RestController
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final ChatStreamService chatStreamService;

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
        return chatService.todo();
    }

    /**
     * 订阅会话事件流（SSE 长连接）
     *
     * <p>
     * 恢复游标优先级：{@code Last-Event-ID} 头（浏览器重连自动携带）优先，
     * 其次 {@code ?since=N}；-1 表示回放全部持久化事件。时序：先订阅事件
     * 中心（实时事件暂存 backlog）→ 回放持久化事件 → goLive 按 seq 去重
     * 刷出 backlog——保证回放期间落库的事件不重不漏（采用先订阅后回放策略）。
     * content_delta 在本端点丢弃：发起回合的 /api/chat/stream 连接已在渲染，
     * 转发会同屏双打；中途加入者靠随后的完整 content 事件补齐
     * </p>
     */
    @GetMapping(path = "/api/chat/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<ResponseBodyEmitter> subscribe(
            @RequestParam String agentId,
            @RequestParam String sessionId,
            @RequestParam(defaultValue = "-1") long since,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        long cursor = resolveCursor(lastEventId, since);
        return sse(chatStreamService.subscribe(agentId, sessionId, cursor));
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
        return sse(chatStreamService.stream(requestParam));
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
