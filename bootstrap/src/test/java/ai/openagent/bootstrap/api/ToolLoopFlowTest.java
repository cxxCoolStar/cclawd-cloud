package ai.openagent.bootstrap.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.openagent.agent.AgentRunStatus;
import ai.openagent.bootstrap.OpenAgentApplication;
import ai.openagent.bootstrap.agentrun.AgentRunCoordinator;
import ai.openagent.bootstrap.agentrun.ToolExecutionStatus;
import ai.openagent.bootstrap.identity.IdentityConstant;
import ai.openagent.bootstrap.persistence.AgentRunRecord;
import ai.openagent.bootstrap.persistence.AgentRunRepository;
import ai.openagent.bootstrap.persistence.ChatMessageRecord;
import ai.openagent.bootstrap.persistence.ChatSessionRepository;
import ai.openagent.bootstrap.persistence.SessionEventRecord;
import ai.openagent.bootstrap.persistence.ToolExecutionRecord;
import ai.openagent.bootstrap.persistence.ToolExecutionRepository;
import ai.openagent.framework.exception.ClientException;
import ai.openagent.infra.ai.LLMService;
import ai.openagent.infra.ai.model.ModelMessage;
import ai.openagent.infra.ai.model.ModelResponse;
import ai.openagent.infra.ai.model.TokenUsage;
import ai.openagent.infra.ai.model.ToolCall;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * Agent 工具闭环集成测试（V2 方案 16.2）
 *
 * <p>
 * 脚本化模型 + 真实 Registry/Invoker/文件工具/持久化：验证
 * 「工具请求 → 工具执行 → 工具结果回传 → 最终文本」全链路的事件顺序、
 * 消息配对持久化、tool_executions / agent_runs 记录与同会话并发 409。
 * 工具走 M4 真实 read_file（目录白名单内、默认启用），测试预置 workspace 文件
 * </p>
 */
@SpringBootTest(
        classes = OpenAgentApplication.class,
        properties = {
            "spring.datasource.url=jdbc:sqlite:target/tool-loop-flow-test.db",
            "openagent.model.api-key=test-key",
            "openagent.model.name=test-model",
            "openagent.tools.workspace-root=target/tool-loop-ws"
        })
@Import(ToolLoopFlowTest.ScriptedModelConfiguration.class)
class ToolLoopFlowTest {

    @Autowired
    private AgentRunCoordinator runCoordinator;

    @Autowired
    private ChatSessionRepository sessionRepository;

    @Autowired
    private AgentRunRepository runRepository;

    @Autowired
    private ToolExecutionRepository executionRepository;

    @Test
    void completesToolRoundTripThroughRealAssembly() throws Exception {
        String sessionId = "tool-loop-" + UUID.randomUUID();
        // 预置会话 workspace 文件供真实 read_file 读取
        Path workspace = Path.of("target", "tool-loop-ws", "default", "sessions", sessionId);
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("data.txt"), "136");

        runCoordinator.start("default", sessionId, "read the data file").get(10, TimeUnit.SECONDS);

        // 消息配对持久化：user → assistant(tool_calls) → tool → assistant(final)
        List<ChatMessageRecord> messages =
                sessionRepository.listMessages(IdentityConstant.LOCAL_USER_ID, "default", sessionId);
        assertEquals(List.of("user", "assistant", "tool", "assistant"),
                messages.stream().map(ChatMessageRecord::role).toList());
        ChatMessageRecord toolMessage = messages.get(2);
        assertEquals("call_read", toolMessage.toolCallId());
        assertEquals("read_file", toolMessage.toolName());
        assertEquals("136", toolMessage.content());
        assertEquals("The file contains 136.", messages.get(3).content());
        // assistant 的 tool_calls 存入 metadata_json 供历史重放
        assertTrue(messages.get(1).metadataJson().contains("call_read"));

        // 事件顺序：tool_call → tool_result → content → done（均已持久化）
        List<SessionEventRecord> events =
                sessionRepository.listEventsSince(IdentityConstant.LOCAL_USER_ID, "default", sessionId, -1);
        assertEquals(List.of("tool_call", "tool_result", "content", "done"),
                events.stream().map(SessionEventRecord::eventType).toList());
        assertTrue(events.get(0).eventData().contains("\"id\":\"call_read\""));
        assertTrue(events.get(1).eventData().contains("136"));

        // agent_runs / tool_executions 记录
        List<AgentRunRecord> runs = runRepository.listBySession(
                IdentityConstant.LOCAL_USER_ID, "default", sessionId, 10);
        assertEquals(1, runs.size());
        AgentRunRecord run = runs.get(0);
        assertEquals(AgentRunStatus.COMPLETED, run.status());
        assertEquals(1, run.toolIterations());
        assertNotNull(run.completedAt());
        List<ToolExecutionRecord> executions = executionRepository.listByRun(run.id());
        assertEquals(1, executions.size());
        assertEquals(ToolExecutionStatus.SUCCEEDED, executions.get(0).status());
        assertEquals("read_file", executions.get(0).toolName());
        assertEquals("136", executions.get(0).resultContent());
    }

    @Test
    void concurrentRunOnSameSessionIsRejected() throws Exception {
        String sessionId = "conflict-" + UUID.randomUUID();
        ScriptedModelConfiguration.HOLD_LATCH = new CountDownLatch(1);
        try {
            CompletableFuture<Void> first = runCoordinator.start("default", sessionId, "hold the turn");
            // 同会话第二次开启必须 409（RESOURCE_CONFLICT）
            assertThrows(ClientException.class,
                    () -> runCoordinator.start("default", sessionId, "second message"));
            ScriptedModelConfiguration.HOLD_LATCH.countDown();
            first.get(10, TimeUnit.SECONDS);
        } finally {
            ScriptedModelConfiguration.HOLD_LATCH = null;
        }
        // 首个运行结束后同会话可再次运行
        runCoordinator.start("default", sessionId, "hello again").get(10, TimeUnit.SECONDS);
    }

    @TestConfiguration
    static class ScriptedModelConfiguration {

        /**
         * 非空时模型调用阻塞等待（并发 409 测试用）
         */
        static volatile CountDownLatch HOLD_LATCH;

        /**
         * 脚本化模型：用户消息含 "read the data file" 且尚无 tool result 时
         * 请求 read_file 工具；已有 tool result 时输出最终回答；其余直接文本
         */
        @Bean
        @Primary
        LLMService scriptedLlmService() {
            return (request, listener) -> {
                CountDownLatch latch = HOLD_LATCH;
                if (latch != null) {
                    try {
                        latch.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                boolean hasToolResult = request.messages().stream()
                        .anyMatch(m -> m.role() == ModelMessage.Role.TOOL);
                boolean asksRead = request.messages().stream()
                        .anyMatch(m -> m.role() == ModelMessage.Role.USER
                                && m.content().contains("read the data file"));
                if (asksRead && !hasToolResult && !request.tools().isEmpty()) {
                    return new ModelResponse.ToolCalls(
                            List.of(new ToolCall("call_read", "read_file", "{\"path\":\"data.txt\"}")),
                            "", TokenUsage.ZERO, "");
                }
                if (hasToolResult) {
                    return new ModelResponse.Text("The file contains 136.", TokenUsage.ZERO, "");
                }
                return new ModelResponse.Text("plain answer", TokenUsage.ZERO, "");
            };
        }
    }
}
