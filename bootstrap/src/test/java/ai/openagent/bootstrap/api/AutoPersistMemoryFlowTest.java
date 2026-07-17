package ai.openagent.bootstrap.api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.openagent.bootstrap.OpenAgentApplication;
import ai.openagent.bootstrap.agentrun.AgentRunCoordinator;
import ai.openagent.bootstrap.identity.IdentityConstant;
import ai.openagent.bootstrap.memory.MemoryService;
import ai.openagent.infra.ai.LLMService;
import ai.openagent.infra.ai.model.ModelMessage;
import ai.openagent.infra.ai.model.ModelResponse;
import ai.openagent.infra.ai.model.TokenUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * 自动记忆提取集成测试（V3 方案 M2）
 *
 * <p>
 * auto-persist-interval=1 保证第一条用户消息即触发；脚本化模型在收到
 * "Analyze this conversation and extract" 提取提示时返回围栏包裹的 JSON；
 * 运行完成后异步提取任务追加 MEMORY.md/USER.md，测试轮询断言文件内容
 * </p>
 */
@SpringBootTest(
        classes = OpenAgentApplication.class,
        properties = {
            "spring.datasource.url=jdbc:sqlite:target/auto-persist-test.db",
            "openagent.model.api-key=test-key",
            "openagent.model.name=test-model",
            "openagent.tools.workspace-root=target/auto-persist-ws",
            "openagent.memory.auto-persist-interval=1"
        })
@Import(AutoPersistMemoryFlowTest.ScriptedModelConfiguration.class)
class AutoPersistMemoryFlowTest {

    @Autowired
    private AgentRunCoordinator runCoordinator;

    @Autowired
    private MemoryService memoryService;

    @Test
    void extractsFactsAndAppendsToMemoryFiles() throws Exception {
        String sessionId = "ap-" + UUID.randomUUID();
        Files.createDirectories(Path.of("target", "auto-persist-ws", "default"));

        runCoordinator.start("default", sessionId, "I prefer concise answers").get(10, TimeUnit.SECONDS);

        // 自动记忆在独立异步任务中执行，轮询等待最多 5 秒
        Path memoryPath = memoryService.agentHome("default").resolve("MEMORY.md");
        Path userPath = memoryService.agentHome("default").resolve("USER.md");
        boolean found = false;
        for (int i = 0; i < 50; i++) {
            if (Files.exists(memoryPath) && Files.exists(userPath)) {
                String memory = Files.readString(memoryPath);
                String user = Files.readString(userPath);
                if (memory.contains("Auto-persisted") && user.contains("concise")) {
                    found = true;
                    break;
                }
            }
            Thread.sleep(100);
        }
        assertTrue(found, "自动记忆应在运行后追加到 MEMORY.md / USER.md");
    }

    @TestConfiguration
    static class ScriptedModelConfiguration {

        @Bean
        @Primary
        LLMService scriptedLlmService() {
            return (request, listener) -> {
                String prompt = request.messages().stream()
                        .filter(m -> m.role() == ModelMessage.Role.USER)
                        .findFirst()
                        .map(ModelMessage::content)
                        .orElse("");
                if (prompt.contains("Analyze this conversation and extract")) {
                    String json = """
                            ```json
                            {"memory_facts": ["user asked about preferences"], "user_notes": ["prefers concise answers"]}
                            ```
                            """;
                    return new ModelResponse.Text(json, TokenUsage.ZERO, "");
                }
                return new ModelResponse.Text("ok", TokenUsage.ZERO, "");
            };
        }
    }
}
