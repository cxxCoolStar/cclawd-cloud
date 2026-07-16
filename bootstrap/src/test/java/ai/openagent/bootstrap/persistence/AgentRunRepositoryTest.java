package ai.openagent.bootstrap.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.openagent.bootstrap.OpenAgentApplication;
import ai.openagent.bootstrap.agentrun.AgentRunStatus;
import ai.openagent.bootstrap.agentrun.ToolExecutionStatus;
import ai.openagent.bootstrap.identity.IdentityConstant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;

/**
 * V2 M1 仓储回归：agent_runs / tool_executions / agent_tools 的增删查改、
 * (run_id, tool_call_id) 唯一约束、sequence 原子分配与启动恢复
 */
@SpringBootTest(
        classes = OpenAgentApplication.class,
        properties = {
            "spring.datasource.url=jdbc:sqlite:target/agent-run-repository-test.db",
            "openagent.model.api-key=test-key",
            "openagent.model.name=test-model"
        })
class AgentRunRepositoryTest {

    @Autowired
    private AgentRunRepository runRepository;

    @Autowired
    private ToolExecutionRepository executionRepository;

    @Autowired
    private AgentToolRepository toolRepository;

    private static AgentRunRecord newRun(String id, AgentRunStatus status) {
        long now = System.currentTimeMillis();
        return new AgentRunRecord(
                id, IdentityConstant.LOCAL_USER_ID, "default", "session-1", status, 0, null, null, now, null, now, now);
    }

    @Test
    void runLifecycleRoundTrips() {
        String runId = "run-" + UUID.randomUUID();
        runRepository.insert(newRun(runId, AgentRunStatus.RUNNING));

        AgentRunRecord created = runRepository.findById(runId).orElseThrow();
        assertEquals(AgentRunStatus.RUNNING, created.status());
        assertNull(created.completedAt());

        runRepository.updateProgress(runId, AgentRunStatus.RUNNING, 3);
        assertEquals(3, runRepository.findById(runId).orElseThrow().toolIterations());

        runRepository.complete(runId, AgentRunStatus.COMPLETED, null, null);
        AgentRunRecord completed = runRepository.findById(runId).orElseThrow();
        assertEquals(AgentRunStatus.COMPLETED, completed.status());
        assertNotNull(completed.completedAt());
        assertTrue(completed.status().isTerminal());
    }

    @Test
    void toolExecutionsAssignSequenceAtomicallyAndEnforceUniqueToolCallId() {
        String runId = "run-" + UUID.randomUUID();
        runRepository.insert(newRun(runId, AgentRunStatus.RUNNING));

        executionRepository.insertRequested("te-1-" + runId, runId, "call-1", "read_file", "{\"path\":\"a\"}");
        executionRepository.insertRequested("te-2-" + runId, runId, "call-2", "calculator", "{\"expr\":\"1+1\"}");

        List<ToolExecutionRecord> executions = executionRepository.listByRun(runId);
        assertEquals(2, executions.size());
        assertEquals(1, executions.get(0).sequence());
        assertEquals(2, executions.get(1).sequence());

        // 同一 run 内 tool_call_id 重复必须被唯一约束拒绝（协议闭合的兜底）。
        // SQLite 驱动不映射 DuplicateKeyException，按 DataAccessException + 根因消息断言
        DataAccessException error = assertThrows(
                DataAccessException.class,
                () -> executionRepository.insertRequested("te-3-" + runId, runId, "call-1", "read_file", "{}"));
        assertTrue(
                String.valueOf(error.getMessage()).contains("UNIQUE constraint failed"),
                "违反的应是 (run_id, tool_call_id) 唯一约束");

        executionRepository.complete(
                "te-1-" + runId, ToolExecutionStatus.SUCCEEDED, "file content", null, null, 42);
        ToolExecutionRecord done = executionRepository.findByToolCallId(runId, "call-1").orElseThrow();
        assertEquals(ToolExecutionStatus.SUCCEEDED, done.status());
        assertEquals("file content", done.resultContent());
        assertEquals(42, done.durationMs());
        assertNotNull(done.completedAt());
    }

    @Test
    void agentToolUpsertPreservesAndOverwrites() {
        String agentId = "agent-" + UUID.randomUUID();
        toolRepository.upsert(agentId, "read_file", true, "{}");
        assertTrue(toolRepository.find(agentId, "read_file").orElseThrow().enabled());

        toolRepository.upsert(agentId, "read_file", false, "{\"maxBytes\":1024}");
        AgentToolRecord updated = toolRepository.find(agentId, "read_file").orElseThrow();
        assertEquals(false, updated.enabled());
        assertEquals("{\"maxBytes\":1024}", updated.configJson());

        toolRepository.upsert(agentId, "calculator", true, "{}");
        assertEquals(List.of("calculator"), toolRepository.listEnabledToolNames(agentId));
    }

    @Test
    void markStaleRunsInterruptedOnlyTouchesActiveRuns() {
        String staleRun = "run-stale-" + UUID.randomUUID();
        String doneRun = "run-done-" + UUID.randomUUID();
        runRepository.insert(newRun(staleRun, AgentRunStatus.RUNNING));
        runRepository.insert(newRun(doneRun, AgentRunStatus.RUNNING));
        runRepository.complete(doneRun, AgentRunStatus.COMPLETED, null, null);

        runRepository.markStaleRunsInterrupted();

        assertEquals(AgentRunStatus.INTERRUPTED, runRepository.findById(staleRun).orElseThrow().status());
        assertEquals(AgentRunStatus.COMPLETED, runRepository.findById(doneRun).orElseThrow().status());
    }
}
