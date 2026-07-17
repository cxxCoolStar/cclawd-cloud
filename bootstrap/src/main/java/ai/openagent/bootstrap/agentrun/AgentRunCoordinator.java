package ai.openagent.bootstrap.agentrun;

import ai.openagent.agent.AgentKernel;
import ai.openagent.agent.AgentRunCommand;
import ai.openagent.agent.AgentRunResult;
import ai.openagent.agent.AgentRunStatus;
import ai.openagent.agent.AgentRuntimeConfig;
import ai.openagent.bootstrap.agentrun.config.AgentProperties;
import ai.openagent.bootstrap.chat.event.ChatEventPublisher;
import ai.openagent.bootstrap.chat.event.ChatSessionKey;
import ai.openagent.bootstrap.identity.IdentityConstant;
import ai.openagent.bootstrap.memory.AutoPersistMemoryService;
import ai.openagent.bootstrap.persistence.AgentRunRecord;
import ai.openagent.bootstrap.persistence.AgentRunRepository;
import ai.openagent.bootstrap.persistence.ChatSessionRepository;
import ai.openagent.bootstrap.tool.config.ToolProperties;
import ai.openagent.framework.errorcode.BaseErrorCode;
import ai.openagent.framework.exception.ClientException;
import ai.openagent.framework.exception.ServiceException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Agent 运行协调器（V2 方案第 5 章）
 *
 * <p>
 * 生命周期职责：同会话单活跃运行（并发 409）、agent_runs 状态持久化
 * （RUNNING → 终态 + tool_iterations）、独立线程池异步执行 Kernel、
 * 落库用户消息后再入循环。浏览器断开只影响 SSE 订阅，不取消运行
 * （V2 方案 6.1 规则 7）；Kernel 内部保证 error/done 必达，此处仅
 * 兜底持久化异常逃逸的场景
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
    private final Executor executor;
    private final Set<String> activeRuns = ConcurrentHashMap.newKeySet();

    private final AutoPersistMemoryService autoPersistMemoryService;

    public AgentRunCoordinator(
            AgentKernel agentKernel,
            AgentRunRepository runRepository,
            ChatSessionRepository sessionRepository,
            ChatEventPublisher eventPublisher,
            AgentProperties agentProperties,
            ToolProperties toolProperties,
            AutoPersistMemoryService autoPersistMemoryService,
            @Qualifier("chatTurnExecutor") Executor executor) {
        this.agentKernel = agentKernel;
        this.runRepository = runRepository;
        this.sessionRepository = sessionRepository;
        this.eventPublisher = eventPublisher;
        this.agentProperties = agentProperties;
        this.toolProperties = toolProperties;
        this.autoPersistMemoryService = autoPersistMemoryService;
        this.executor = executor;
    }

    /**
     * 开启一次异步 Agent 运行
     *
     * @return 运行完成信号（事件流才是主通道，调用方通常不等待）
     */
    public CompletableFuture<Void> start(String agentId, String sessionId, String message) {
        if (sessionId == null || sessionId.isBlank() || sessionId.length() > 128) {
            throw new ClientException("valid sessionId required");
        }
        if (message == null || message.isBlank()) {
            throw new ClientException("message required");
        }
        String key = new ChatSessionKey(agentId, sessionId).compact();
        if (!activeRuns.add(key)) {
            throw new ClientException(
                    "a chat turn is already running for this session", BaseErrorCode.RESOURCE_CONFLICT);
        }

        String userId = IdentityConstant.LOCAL_USER_ID;
        String runId = UUID.randomUUID().toString();
        try {
            // 先落库用户消息与 run 记录，模型调用前的失败直接以 HTTP 错误返回
            sessionRepository.ensureSession(userId, agentId, sessionId, message);
            sessionRepository.appendMessage(userId, agentId, sessionId, "user", message, "", "");
            long now = System.currentTimeMillis();
            runRepository.insert(new AgentRunRecord(
                    runId, userId, agentId, sessionId, AgentRunStatus.RUNNING, 0, null, null,
                    now, null, now, now));

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
            return CompletableFuture.runAsync(() -> execute(command, sink), executor)
                    .whenComplete((ignored, error) -> {
                        activeRuns.remove(key);
                        if (error != null) {
                            // Kernel 已保证 error/done 必达；此处兜底事件持久化
                            // 本身失败导致的异常逃逸（发瞬时事件收敛前端）
                            log.error("[agentrun] 运行异常逃逸，补发收敛事件，runId={}", runId, error);
                            markFailedQuietly(runId, "RUN_ESCAPED", rootMessage(error));
                            eventPublisher.publishTransient(agentId, sessionId, "error",
                                    Map.of("message", "agent run failed unexpectedly"));
                            eventPublisher.publishTransient(agentId, sessionId, "done", Map.of());
                        } else {
                            // V3 M2：自动记忆提取以 fire-and-forget 方式触发，
                            // 避免 session 锁被占用；失败不影响运行终态
                            try {
                                executor.execute(() -> autoPersistMemoryService.maybePersist(
                                        IdentityConstant.LOCAL_USER_ID, agentId, sessionId));
                            } catch (RuntimeException fireError) {
                                log.warn("[agentrun] 自动记忆提取任务入队失败", fireError);
                            }
                        }
                    });
        } catch (RejectedExecutionException error) {
            activeRuns.remove(key);
            markFailedQuietly(runId, "EXECUTOR_BUSY", "chat executor is busy");
            log.warn("[agentrun] 回合线程池已满，拒绝新运行，agentId={}, sessionId={}", agentId, sessionId);
            throw new ServiceException(
                    "chat executor is busy", error, BaseErrorCode.SERVICE_UNAVAILABLE_ERROR);
        } catch (RuntimeException error) {
            activeRuns.remove(key);
            throw error;
        }
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
}
