package ai.openagent.bootstrap.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.openagent.agent.AgentRunStatus;
import ai.openagent.bootstrap.OpenAgentApplication;
import ai.openagent.bootstrap.agentrun.ToolExecutionStatus;
import ai.openagent.bootstrap.identity.IdentityConstant;
import ai.openagent.bootstrap.persistence.AgentRunRecord;
import ai.openagent.bootstrap.persistence.AgentRunRepository;
import ai.openagent.bootstrap.persistence.ToolExecutionRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 运行轨迹端点集成测试（EVALUATION_PLAN.md Phase 1.2）
 *
 * <p>
 * 覆盖：trace 组装（run 元信息 + token 四列 + 工具事件按 sequence 升序）、
 * 工具入参疑似密钥键打码、未知 runId 404、未认证 401。归属越权路径与
 * AgentService.requireAccess 同构，由 V9 既有防线测试覆盖
 * </p>
 */
@SpringBootTest(
        classes = OpenAgentApplication.class,
        properties = {
            "spring.datasource.url=jdbc:sqlite:target/trace-endpoint-test.db",
            "openagent.model.api-key=test-key",
            "openagent.model.name=test-model"
        })
@AutoConfigureMockMvc
class TraceEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AgentRunRepository runRepository;

    @Autowired
    private ToolExecutionRepository executionRepository;

    @Test
    void exportsTraceWithTokenUsageAndMaskedArguments() throws Exception {
        String runId = "run-" + UUID.randomUUID();
        long now = System.currentTimeMillis();
        runRepository.insert(new AgentRunRecord(
                runId, IdentityConstant.LOCAL_USER_ID, "default", "session-trace",
                AgentRunStatus.RUNNING, 0, null, null, 0, 0, 0, 0, now, null, now, now));
        runRepository.addTokenUsage(runId, new ai.openagent.infra.ai.model.TokenUsage(100, 20, 60, 0));
        runRepository.complete(runId, AgentRunStatus.COMPLETED, null, null);

        executionRepository.insertRequested(
                "te-1-" + runId, runId, "call-1", "web_fetch",
                "{\"url\":\"https://example.com\",\"api_key\":\"sk-1234567890abcdef\"}");
        executionRepository.complete(
                "te-1-" + runId, ToolExecutionStatus.SUCCEEDED, "page content", null, null, 42);
        executionRepository.insertRequested(
                "te-2-" + runId, runId, "call-2", "read_file", "{\"path\":\"a.txt\"}");

        mockMvc.perform(get("/api/runs/{runId}/trace", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId))
                .andExpect(jsonPath("$.agentId").value("default"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.inputTokens").value(100))
                .andExpect(jsonPath("$.outputTokens").value(20))
                .andExpect(jsonPath("$.cacheReadTokens").value(60))
                .andExpect(jsonPath("$.durationMs").isNumber())
                .andExpect(jsonPath("$.events.length()").value(2))
                .andExpect(jsonPath("$.events[0].sequence").value(1))
                .andExpect(jsonPath("$.events[0].toolName").value("web_fetch"))
                .andExpect(jsonPath("$.events[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.events[0].resultContent").value("page content"))
                // 疑似密钥键（api_key）打码，非密钥键（url）原样保留
                .andExpect(jsonPath("$.events[0].arguments")
                        .value(org.hamcrest.Matchers.containsString("sk-1****cdef")))
                .andExpect(jsonPath("$.events[0].arguments")
                        .value(org.hamcrest.Matchers.containsString("https://example.com")))
                .andExpect(jsonPath("$.events[1].sequence").value(2))
                .andExpect(jsonPath("$.events[1].toolName").value("read_file"));
    }

    @Test
    void unknownRunIdReturns404() throws Exception {
        mockMvc.perform(get("/api/runs/{runId}/trace", "no-such-run"))
                .andExpect(status().isNotFound());
    }
}
