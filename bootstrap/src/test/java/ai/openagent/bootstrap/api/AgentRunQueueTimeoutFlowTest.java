package ai.openagent.bootstrap.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.openagent.agent.AgentRunStatus;
import ai.openagent.bootstrap.OpenAgentApplication;
import ai.openagent.bootstrap.agentrun.AgentRunCoordinator;
import ai.openagent.bootstrap.identity.IdentityConstant;
import ai.openagent.bootstrap.persistence.AgentRunRecord;
import ai.openagent.bootstrap.persistence.AgentRunRepository;
import ai.openagent.framework.errorcode.BaseErrorCode;
import ai.openagent.framework.exception.ServiceException;
import ai.openagent.infra.ai.LLMService;
import ai.openagent.infra.ai.model.ModelMessage;
import ai.openagent.infra.ai.model.ModelResponse;
import ai.openagent.infra.ai.model.TokenUsage;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * 会话级 FIFO 排队超时集成测试（V8 M2）
 *
 * <p>
 * 首个运行被持锁阻塞时，同会话第二条消息排队超过
 * {@code openagent.chat.queue-wait-timeout} 后被移出队列：返回的 future
 * 以超时业务错误（SERVICE_TIMEOUT_ERROR → 429）异常完成，run 记录标记
 * FAILED / QUEUE_WAIT_TIMEOUT；首个运行不受影响照常完成
 * </p>
 */
@SpringBootTest(
        classes = OpenAgentApplication.class,
        properties = {
            "spring.datasource.url=jdbc:sqlite:target/queue-timeout-flow-test.db",
            "openagent.model.api-key=test-key",
            "openagent.model.name=test-model",
            "openagent.tools.workspace-root=target/queue-timeout-ws",
            "openagent.chat.queue-wait-timeout=1s"
        })
@Import(AgentRunQueueTimeoutFlowTest.ScriptedModelConfiguration.class)
class AgentRunQueueTimeoutFlowTest {

    @Autowired
    private AgentRunCoordinator runCoordinator;

    @Autowired
    private AgentRunRepository runRepository;

    @Test
    void queuedRunExpiresAfterQueueWaitTimeout() throws Exception {
        String sessionId = "queue-timeout-" + UUID.randomUUID();
        ScriptedModelConfiguration.HOLD_LATCH = new CountDownLatch(1);
        CompletableFuture<Void> first;
        try {
            first = runCoordinator.start("default", sessionId, "hold the turn");
            CompletableFuture<Void> second = runCoordinator.start("default", sessionId, "second message");

            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> second.get(10, TimeUnit.SECONDS));
            ServiceException cause = assertInstanceOf(ServiceException.class, error.getCause());
            assertEquals(BaseErrorCode.SERVICE_TIMEOUT_ERROR.code(), cause.getErrorCode());

            // 超时的 run 标记 FAILED / QUEUE_WAIT_TIMEOUT，且不再出队执行
            List<AgentRunRecord> runs = runRepository.listBySession(
                    IdentityConstant.LOCAL_USER_ID, "default", sessionId, 10);
            assertEquals(1, runs.stream()
                    .filter(run -> run.status() == AgentRunStatus.FAILED
                            && "QUEUE_WAIT_TIMEOUT".equals(run.errorCode()))
                    .count());
        } finally {
            ScriptedModelConfiguration.HOLD_LATCH.countDown();
            ScriptedModelConfiguration.HOLD_LATCH = null;
        }
        // 首个运行不受排队超时影响，照常完成
        first.get(10, TimeUnit.SECONDS);
        List<AgentRunRecord> runs = runRepository.listBySession(
                IdentityConstant.LOCAL_USER_ID, "default", sessionId, 10);
        assertTrue(runs.stream().anyMatch(run -> run.status() == AgentRunStatus.COMPLETED));
    }

    @TestConfiguration
    static class ScriptedModelConfiguration {

        /**
         * 非空时含 "hold" 的用户消息的模型调用阻塞等待
         */
        static volatile CountDownLatch HOLD_LATCH;

        @Bean
        @Primary
        LLMService scriptedLlmService() {
            return (request, listener) -> {
                CountDownLatch latch = HOLD_LATCH;
                boolean asksHold = request.messages().stream()
                        .anyMatch(m -> m.role() == ModelMessage.Role.USER
                                && m.content().contains("hold"));
                if (latch != null && asksHold) {
                    try {
                        latch.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return new ModelResponse.Text("plain answer", TokenUsage.ZERO, "");
            };
        }
    }
}
