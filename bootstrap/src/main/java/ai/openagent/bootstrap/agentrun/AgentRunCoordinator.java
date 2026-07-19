package ai.openagent.bootstrap.agentrun;

import ai.openagent.agent.AgentKernel;
import ai.openagent.agent.AgentRunCommand;
import ai.openagent.agent.AgentRunResult;
import ai.openagent.agent.AgentRunStatus;
import ai.openagent.agent.AgentRuntimeConfig;
import ai.openagent.bootstrap.agentrun.config.AgentProperties;
import ai.openagent.bootstrap.chat.config.ChatProperties;
import ai.openagent.bootstrap.chat.event.ChatEventPublisher;
import ai.openagent.bootstrap.chat.event.ChatSessionKey;
import ai.openagent.bootstrap.memory.AutoPersistMemoryService;
import ai.openagent.bootstrap.persistence.AgentRunRecord;
import ai.openagent.bootstrap.persistence.AgentRunRepository;
import ai.openagent.bootstrap.persistence.ChatSessionRepository;
import ai.openagent.bootstrap.tool.config.ToolProperties;
import ai.openagent.bootstrap.workspace.WorkspaceHistoryService;
import ai.openagent.framework.errorcode.BaseErrorCode;
import ai.openagent.framework.exception.ClientException;
import ai.openagent.framework.exception.ServiceException;
import ai.openagent.framework.identity.RequestContext;
import jakarta.annotation.PreDestroy;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Agent 运行协调器（V2 方案第 5 章；V8 M2 会话级 FIFO 队列）
 *
 * <p>
 * 生命周期职责：同会话 FIFO 排队（对齐 fastclaw internal/taskqueue 的
 * per-chat 队列语义，V8 起替代同会话并发 409）、agent_runs 状态持久化
 * （CREATED 排队 → RUNNING → 终态 + tool_iterations）、独立线程池异步
 * 执行 Kernel、落库用户消息后再入队。浏览器断开只影响 SSE 订阅，不取消
 * 排队/运行中的 run（V2 方案 6.1 规则 7）；Kernel 内部保证 error/done
 * 必达，此处仅兜底持久化异常逃逸的场景
 *
 * <p>
 * 队列语义：提交即落库用户消息与 run 记录（CREATED）并入队，同会话
 * 按提交顺序串行执行；跨会话并行，全局并发由 chatTurnExecutor 有界
 * 线程池承载（fastclaw 全局信号量等价物）。排队超过
 * {@code openagent.chat.queue-wait-timeout}（默认 60s）的 run 被移出
 * 队列、标记 FAILED（QUEUE_WAIT_TIMEOUT）并补发 error/done 瞬时事件
 * 收敛前端，返回的 future 以超时业务错误（429）完成。activeRuns 保留
 * 但语义为"运行中"登记。空队列对象常驻 map（规模受会话数约束），
 * 换取"判空停派"与"入队开派"在队列锁内天然互斥，无需额外并发控制
 * </p>
 */
@Slf4j
@Component
public class AgentRunCoordinator {

    private final AgentKernel agentKernel;
    private final AgentRunRepository runRepository;
    private final ChatSessionRepository sessionRepository;
    private final ChatEventPublisher eventPublisher;
    private final AgentProperties agentProperties;
    private final ToolProperties toolProperties;
    private final ChatProperties chatProperties;
    private final Executor executor;
    private final Set<String> activeRuns = ConcurrentHashMap.newKeySet();
    private final Map<String, SessionQueue> sessionQueues = new ConcurrentHashMap<>();
    private final ScheduledExecutorService queueTimeoutScheduler;

    private final AutoPersistMemoryService autoPersistMemoryService;
    private final WorkspaceHistoryService workspaceHistoryService;

    public AgentRunCoordinator(
            AgentKernel agentKernel,
            AgentRunRepository runRepository,
            ChatSessionRepository sessionRepository,
            ChatEventPublisher eventPublisher,
            AgentProperties agentProperties,
            ToolProperties toolProperties,
            ChatProperties chatProperties,
            AutoPersistMemoryService autoPersistMemoryService,
            WorkspaceHistoryService workspaceHistoryService,
            @Qualifier("chatTurnExecutor") Executor executor) {
        this.agentKernel = agentKernel;
        this.runRepository = runRepository;
        this.sessionRepository = sessionRepository;
        this.eventPublisher = eventPublisher;
        this.agentProperties = agentProperties;
        this.toolProperties = toolProperties;
        this.chatProperties = chatProperties;
        this.autoPersistMemoryService = autoPersistMemoryService;
        this.workspaceHistoryService = workspaceHistoryService;
        this.executor = executor;
        this.queueTimeoutScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "chat-queue-timeout");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    void shutdown() {
        queueTimeoutScheduler.shutdownNow();
    }

    /**
     * 开启一次异步 Agent 运行（同会话排队，提交即落库）
     *
     * @return 运行完成信号（事件流才是主通道，调用方通常不等待）；
     *         排队超时或执行异常逃逸时以异常完成
     */
    public CompletableFuture<Void> start(String agentId, String sessionId, String message) {
        if (sessionId == null || sessionId.isBlank() || sessionId.length() > 128) {
            throw new ClientException("valid sessionId required");
        }
        if (message == null || message.isBlank()) {
            throw new ClientException("message required");
        }
        String key = new ChatSessionKey(agentId, sessionId).compact();

        // 提交时在请求线程快照当前用户：ThreadLocal 不跨线程边界，
        // 排队/执行线程只读 command 里携带的 userId
        String userId = RequestContext.requireUserId();
        String runId = UUID.randomUUID().toString();
        // 先落库用户消息与 run 记录（CREATED = 排队中），模型调用前的失败
        // 直接以 HTTP 错误返回；提交即落库保证同会话顺序与可见性
        sessionRepository.ensureSession(userId, agentId, sessionId, message);
        sessionRepository.appendMessage(userId, agentId, sessionId, "user", message, "", "");
        long now = System.currentTimeMillis();
        runRepository.insert(new AgentRunRecord(
                runId, userId, agentId, sessionId, AgentRunStatus.CREATED, 0, null, null,
                0, 0, 0, 0, now, null, now, now));

        AgentRunCommand command = new AgentRunCommand(
                runId,
                userId,
                agentId,
                sessionId,
                message,
                new AgentRuntimeConfig(
                        agentProperties.maxToolIterations(),
                        agentProperties.runTimeout(),
                        toolProperties.executionTimeout()));
        WireAgentEventSink sink = new WireAgentEventSink(eventPublisher, userId, agentId, sessionId);
        QueuedRun queued = new QueuedRun(command, sink);
        SessionQueue queue = sessionQueues.computeIfAbsent(key, ignored -> new SessionQueue());
        queued.timeoutTask = queueTimeoutScheduler.schedule(
                () -> expireQueuedRun(queue, queued),
                chatProperties.queueWaitTimeout().toMillis(),
                TimeUnit.MILLISECONDS);
        boolean dispatch;
        synchronized (queue) {
            queue.pending.addLast(queued);
            dispatch = !queue.running;
            if (dispatch) {
                queue.running = true;
            }
        }
        if (dispatch) {
            dispatchNext(key, queue);
        }
        return queued.completion;
    }

    /**
     * 派出队首 run；队空则停派（running=false，等待下次入队开派）
     */
    private void dispatchNext(String key, SessionQueue queue) {
        QueuedRun next;
        synchronized (queue) {
            next = queue.pending.pollFirst();
            if (next == null) {
                queue.running = false;
                return;
            }
        }
        if (next.timeoutTask != null) {
            next.timeoutTask.cancel(false);
        }
        activeRuns.add(key);
        runRepository.updateProgress(next.command.runId(), AgentRunStatus.RUNNING, 0);
        try {
            CompletableFuture.runAsync(() -> execute(next.command, next.sink), executor)
                    .whenComplete((ignored, error) -> {
                        activeRuns.remove(key);
                        if (error != null) {
                            // Kernel 已保证 error/done 必达；此处兜底事件持久化
                            // 本身失败导致的异常逃逸（发瞬时事件收敛前端）
                            log.error("[agentrun] 运行异常逃逸，补发收敛事件，runId={}", next.command.runId(), error);
                            markFailedQuietly(next.command.runId(), "RUN_ESCAPED", rootMessage(error));
                            eventPublisher.publishTransient(
                                    next.command.agentId(), next.command.sessionId(), "error",
                                    Map.of("message", "agent run failed unexpectedly"));
                            eventPublisher.publishTransient(
                                    next.command.agentId(), next.command.sessionId(), "done", Map.of());
                            next.completion.completeExceptionally(error);
                        } else {
                            // V3 M2：自动记忆提取以 fire-and-forget 方式触发，
                            // 避免 session 锁被占用；失败不影响运行终态
                            try {
                                executor.execute(() -> autoPersistMemoryService.maybePersist(
                                        next.command.userId(),
                                        next.command.agentId(),
                                        next.command.sessionId()));
                                // workspace 版本历史：turn 边界快照（覆盖文件工具
                                // + exec + 上传的全部写入）
                                executor.execute(() -> workspaceHistoryService.commitAfterRun(
                                        next.command.agentId(), next.command.sessionId(), next.command.runId()));
                            } catch (RuntimeException fireError) {
                                log.warn("[agentrun] 自动记忆提取任务入队失败", fireError);
                            }
                            next.completion.complete(null);
                        }
                        dispatchNext(key, queue);
                    });
        } catch (RejectedExecutionException error) {
            activeRuns.remove(key);
            markFailedQuietly(next.command.runId(), "EXECUTOR_BUSY", "chat executor is busy");
            log.warn("[agentrun] 回合线程池已满，运行失败，runId={}, agentId={}, sessionId={}",
                    next.command.runId(), next.command.agentId(), next.command.sessionId());
            next.completion.completeExceptionally(new ServiceException(
                    "chat executor is busy", error, BaseErrorCode.SERVICE_UNAVAILABLE_ERROR));
            // 线程池拒绝只失败当前 run，后续排队 run 继续尝试（线程池可能已恢复）
            dispatchNext(key, queue);
        }
    }

    /**
     * 排队等待超时：仍在队列中则移除、标记失败并补发收敛事件
     */
    private void expireQueuedRun(SessionQueue queue, QueuedRun queued) {
        boolean removed;
        synchronized (queue) {
            removed = queue.pending.remove(queued);
        }
        if (!removed) {
            return; // 已派出执行（或已被处理），超时不适用于运行中的 run
        }
        log.warn("[agentrun] 排队等待超时，移出队列，runId={}, agentId={}, sessionId={}",
                queued.command.runId(), queued.command.agentId(), queued.command.sessionId());
        markFailedQuietly(queued.command.runId(), "QUEUE_WAIT_TIMEOUT", "chat queue wait timeout");
        eventPublisher.publishTransient(queued.command.agentId(), queued.command.sessionId(), "error",
                Map.of("message", "chat queue wait timeout"));
        eventPublisher.publishTransient(queued.command.agentId(), queued.command.sessionId(), "done", Map.of());
        queued.completion.completeExceptionally(
                new ServiceException("chat queue wait timeout", BaseErrorCode.SERVICE_TIMEOUT_ERROR));
    }

    private void execute(AgentRunCommand command, WireAgentEventSink sink) {
        long startedAt = System.currentTimeMillis();
        log.info("[agentrun] 运行开始，runId={}, agentId={}, sessionId={}",
                command.runId(), command.agentId(), command.sessionId());
        AgentRunResult result = agentKernel.run(command, sink);
        runRepository.updateProgress(command.runId(), result.status(), result.toolIterations());
        runRepository.complete(
                command.runId(),
                result.status(),
                result.errorCode().isBlank() ? null : result.errorCode(),
                result.errorMessage().isBlank() ? null : result.errorMessage());
        log.info("[agentrun] 运行结束，runId={}, status={}, iterations={}, 耗时 {}ms",
                command.runId(), result.status(), result.toolIterations(),
                System.currentTimeMillis() - startedAt);
    }

    private void markFailedQuietly(String runId, String errorCode, String errorMessage) {
        try {
            runRepository.complete(runId, AgentRunStatus.FAILED, errorCode, errorMessage);
        } catch (RuntimeException error) {
            log.error("[agentrun] run 终态持久化失败，runId={}", runId, error);
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    /**
     * 同会话排队中的一个 run
     */
    private static final class QueuedRun {

        private final AgentRunCommand command;
        private final WireAgentEventSink sink;
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private volatile ScheduledFuture<?> timeoutTask;

        private QueuedRun(AgentRunCommand command, WireAgentEventSink sink) {
            this.command = command;
            this.sink = sink;
        }
    }

    /**
     * 单会话队列：FIFO 待派队列 + 派发中标志（所有访问持本对象锁）
     */
    private static final class SessionQueue {

        private final ArrayDeque<QueuedRun> pending = new ArrayDeque<>();
        private boolean running;
    }
}
