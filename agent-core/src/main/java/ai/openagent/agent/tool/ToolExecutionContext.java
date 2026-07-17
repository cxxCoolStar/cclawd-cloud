package ai.openagent.agent.tool;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * 工具执行上下文（V2 方案 3.1 工具框架）
 *
 * @param runId     所属 Agent run
 * @param userId    用户 ID（本地模式固定 local-user，保留数据边界）
 * @param agentId   Agent ID
 * @param sessionId 会话 ID
 * @param workspace 会话 workspace 目录
 *                  （{workspaceRoot}/{agentId}/sessions/{sessionId}）
 * @param deadline  本次执行的截止时间（run 总超时与单工具超时取早者）
 */
public record ToolExecutionContext(
        String runId,
        String userId,
        String agentId,
        String sessionId,
        Path workspace,
        Instant deadline) {

    public ToolExecutionContext {
        runId = Objects.requireNonNull(runId, "runId");
        userId = Objects.requireNonNull(userId, "userId");
        agentId = Objects.requireNonNull(agentId, "agentId");
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        workspace = Objects.requireNonNull(workspace, "workspace");
        deadline = Objects.requireNonNull(deadline, "deadline");
    }
}
