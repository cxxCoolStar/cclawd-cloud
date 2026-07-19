package ai.openagent.bootstrap.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.openagent.bootstrap.OpenAgentApplication;
import ai.openagent.bootstrap.agentrun.AgentRunCoordinator;
import ai.openagent.bootstrap.identity.IdentityConstant;
import ai.openagent.bootstrap.persistence.ChatMessageRecord;
import ai.openagent.bootstrap.persistence.ChatSessionRepository;
import ai.openagent.infra.ai.LLMService;
import ai.openagent.infra.ai.model.ModelMessage;
import ai.openagent.infra.ai.model.ModelResponse;
import ai.openagent.infra.ai.model.TokenUsage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * 上下文压缩集成测试（V3 方案 M1 退出条件）
 *
 * <p>
 * 极小阈值（200 token / 保留尾部 4 条）+ 预置超长历史 + 脚本化模型：
 * 验证压缩在真实装配中触发——模型收到的上下文以 [Conversation Summary]
 * 开头拼接近期尾部、总结调用不携带 tools、压缩前完整历史落盘
 * memory/logs、session_messages 持久化历史不被改写（V3 方案 4.1）
 * </p>
 */
@SpringBootTest(
        classes = OpenAgentApplication.class,
        properties = {
            "spring.datasource.url=jdbc:sqlite:target/compaction-flow-test.db",
            "openagent.model.api-key=test-key",
            "openagent.model.name=test-model",
            "openagent.tools.workspace-root=target/compaction-ws",
            "openagent.agent.context-token-threshold=200",
            "openagent.agent.context-prune-turn-age=4"
        })
@Import(ContextCompactionFlowTest.ScriptedModelConfiguration.class)
class ContextCompactionFlowTest {

    @Autowired
    private AgentRunCoordinator runCoordinator;

    @Autowired
    private ChatSessionRepository sessionRepository;

    @Test
    void compactsLongHistoryBeforeModelCall() throws Exception {
        String userId = IdentityConstant.LOCAL_USER_ID;
        String sessionId = "compact-" + UUID.randomUUID();
        // 清理历史运行遗留的 workspace（logDir 按 agent 共享，跨运行会累积）
        Path logDir = Path.of("target", "compaction-ws", "default", "memory", "logs");
        if (Files.exists(logDir)) {
            try (Stream<Path> files = Files.list(logDir)) {
                for (Path file : files.toList()) {
                    Files.deleteIfExists(file);
                }
            }
        }
        // 预置 6 条超长历史（各约 100 token），与本轮新消息合计远超 200 阈值：
        // 旧段含一对 assistant(tool_calls) + tool 配对，验证配对不被压缩破坏
        sessionRepository.appendMessage(userId, "default", sessionId, "user", "x".repeat(400), "", "");
        sessionRepository.appendMessage(userId, "default", sessionId, "assistant", "", "", "",
                "", "", "{\"toolCallsJson\":\"[{\\\"id\\\":\\\"c1\\\",\\\"name\\\":\\\"read_file\\\",\\\"arguments\\\":\\\"{}\\\"}]\"}");
        sessionRepository.appendMessage(userId, "default", sessionId, "tool", "y".repeat(400), "", "", "c1", "read_file", "");
        sessionRepository.appendMessage(userId, "default", sessionId, "user", "z".repeat(400), "", "");
        sessionRepository.appendMessage(userId, "default", sessionId, "assistant", "w".repeat(400), "", "");
        sessionRepository.appendMessage(userId, "default", sessionId, "user", "v".repeat(400), "", "");

        TestIdentity.callAs(TestIdentity.localUser(),
                () -> runCoordinator.start("default", sessionId, "tail question").get(10, TimeUnit.SECONDS));

        // 总结调用：system prompt 为 summarizer 提示、不携带 tools
        List<ModelMessage> summaryRequest = ScriptedModelConfiguration.RECEIVED.stream()
                .filter(messages -> messages.get(0).content().contains("conversation summarizer"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未发生总结调用"));
        assertTrue(ScriptedModelConfiguration.SUMMARY_REQUEST_HAD_NO_TOOLS);

        // 正式调用：上下文 = system + [Conversation Summary] + 近期尾部（4 条），
        // 尾部首条不得是 role=tool（协议红线）
        List<ModelMessage> chatRequest = ScriptedModelConfiguration.RECEIVED.stream()
                .filter(messages -> !messages.get(0).content().contains("conversation summarizer"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未发生正式模型调用"));
        assertEquals(ModelMessage.Role.SYSTEM, chatRequest.get(0).role());
        assertEquals(ModelMessage.Role.USER, chatRequest.get(1).role());
        assertTrue(chatRequest.get(1).content().startsWith("[Conversation Summary]"),
                "压缩后第二条应为摘要消息，实际: " + chatRequest.get(1));
        for (int i = 1; i < chatRequest.size(); i++) {
            assertNotEquals(ModelMessage.Role.TOOL, chatRequest.get(i).role(),
                    "压缩后的上下文中不应残留 tool 消息（切割点已前移吞并进摘要）: index=" + i);
        }
        // 尾部保留最近消息
        assertTrue(chatRequest.stream().anyMatch(m -> m.content().contains("tail question")));

        // 压缩前完整历史已落盘 memory/logs
        try (Stream<Path> files = Files.list(logDir)) {
            List<Path> logs = files.filter(p -> p.getFileName().toString().startsWith("history_")).toList();
            assertEquals(1, logs.size(), "应恰好落盘一份历史日志");
            List<String> lines = Files.readAllLines(logs.get(0));
            assertEquals(8, lines.size(), "落盘应为压缩前的完整历史（system + 6 条预置 + 1 条本轮）");
        }

        // session_messages 持久化历史不被改写（V3 方案 4.1）
        List<ChatMessageRecord> persisted = sessionRepository.listMessages(userId, "default", sessionId);
        assertEquals("y".repeat(400), persisted.get(2).content(), "tool 结果原文必须仍在持久化历史中");
    }

    @TestConfiguration
    static class ScriptedModelConfiguration {

        /**
         * 模型收到的全部请求消息列表（按调用顺序）
         */
        static final List<List<ModelMessage>> RECEIVED = new CopyOnWriteArrayList<>();

        static volatile boolean SUMMARY_REQUEST_HAD_NO_TOOLS = false;

        @Bean
        @Primary
        LLMService scriptedLlmService() {
            return (request, listener) -> {
                RECEIVED.add(request.messages());
                boolean isSummarizer = request.messages().stream()
                        .anyMatch(m -> m.role() == ModelMessage.Role.SYSTEM
                                && m.content().contains("conversation summarizer"));
                if (isSummarizer) {
                    SUMMARY_REQUEST_HAD_NO_TOOLS = request.tools().isEmpty();
                    return new ModelResponse.Text("compact summary of old turns", TokenUsage.ZERO, "");
                }
                return new ModelResponse.Text("final answer", TokenUsage.ZERO, "");
            };
        }
    }
}
