package ai.openagent.bootstrap.persistence;

import ai.openagent.agent.AgentRunStatus;
import ai.openagent.infra.ai.model.TokenUsage;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Agent 运行仓储
 */
@Repository
@RequiredArgsConstructor
public class AgentRunRepository {

    private static final String SELECT_COLUMNS =
            "SELECT id, user_id, agent_id, session_id, status, tool_iterations, error_code, error_message, input_tokens, output_tokens, cache_read_tokens, cache_write_tokens, started_at, completed_at, created_at, updated_at FROM agent_runs";

    private static final RowMapper<AgentRunRecord> ROW_MAPPER = (rs, row) -> new AgentRunRecord(
            rs.getString("id"),
            rs.getString("user_id"),
            rs.getString("agent_id"),
            rs.getString("session_id"),
            AgentRunStatus.valueOf(rs.getString("status")),
            rs.getInt("tool_iterations"),
            rs.getString("error_code"),
            rs.getString("error_message"),
            rs.getLong("input_tokens"),
            rs.getLong("output_tokens"),
            rs.getLong("cache_read_tokens"),
            rs.getLong("cache_write_tokens"),
            rs.getLong("started_at"),
            (Long) rs.getObject("completed_at"),
            rs.getLong("created_at"),
            rs.getLong("updated_at"));

    private final JdbcTemplate jdbc;

    /**
     * 创建运行记录（初始状态 CREATED 或 RUNNING）
     */
    public void insert(AgentRunRecord run) {
        jdbc.update(
                "INSERT INTO agent_runs (id, user_id, agent_id, session_id, status, tool_iterations, error_code, error_message, input_tokens, output_tokens, cache_read_tokens, cache_write_tokens, started_at, completed_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                run.id(),
                run.userId(),
                run.agentId(),
                run.sessionId(),
                run.status().name(),
                run.toolIterations(),
                run.errorCode(),
                run.errorMessage(),
                run.inputTokens(),
                run.outputTokens(),
                run.cacheReadTokens(),
                run.cacheWriteTokens(),
                run.startedAt(),
                run.completedAt(),
                run.createdAt(),
                run.updatedAt());
    }

    /**
     * 按 ID 查询运行
     */
    public Optional<AgentRunRecord> findById(String id) {
        return jdbc.query(SELECT_COLUMNS + " WHERE id = ?", ROW_MAPPER, id).stream().findFirst();
    }

    /**
     * 更新运行状态与迭代计数（非终态推进）
     */
    public void updateProgress(String id, AgentRunStatus status, int toolIterations) {
        jdbc.update(
                "UPDATE agent_runs SET status = ?, tool_iterations = ?, updated_at = ? WHERE id = ?",
                status.name(),
                toolIterations,
                System.currentTimeMillis(),
                id);
    }

    /**
     * 累加一次模型调用的 token 用量（AFTER_MODEL_CALL hook 逐次增量，
     * 见 EVALUATION_PLAN.md Phase 1.1——hook 无状态，跨调用聚合靠 SQL 自增）
     */
    public void addTokenUsage(String id, TokenUsage usage) {
        jdbc.update(
                "UPDATE agent_runs SET input_tokens = input_tokens + ?, output_tokens = output_tokens + ?, cache_read_tokens = cache_read_tokens + ?, cache_write_tokens = cache_write_tokens + ?, updated_at = ? WHERE id = ?",
                usage.inputTokens(),
                usage.outputTokens(),
                usage.cacheReadTokens(),
                usage.cacheWriteTokens(),
                System.currentTimeMillis(),
                id);
    }

    /**
     * 以终态完结运行
     */
    public void complete(String id, AgentRunStatus status, String errorCode, String errorMessage) {
        long now = System.currentTimeMillis();
        jdbc.update(
                "UPDATE agent_runs SET status = ?, error_code = ?, error_message = ?, completed_at = ?, updated_at = ? WHERE id = ?",
                status.name(),
                errorCode,
                errorMessage,
                now,
                now,
                id);
    }

    /**
     * 查询会话最近的运行记录（按创建时间倒序）
     */
    public List<AgentRunRecord> listBySession(String userId, String agentId, String sessionId, int limit) {
        return jdbc.query(
                SELECT_COLUMNS + " WHERE user_id = ? AND agent_id = ? AND session_id = ? ORDER BY created_at DESC LIMIT ?",
                ROW_MAPPER,
                userId,
                agentId,
                sessionId,
                limit);
    }

    /**
     * 启动恢复：将非终态的遗留运行标记为 INTERRUPTED（V2 不做断点续跑），
     * 返回受影响行数
     */
    public int markStaleRunsInterrupted() {
        long now = System.currentTimeMillis();
        return jdbc.update(
                "UPDATE agent_runs SET status = ?, error_code = 'INTERRUPTED', error_message = 'server restarted while run was active', completed_at = ?, updated_at = ? WHERE status IN (?, ?)",
                AgentRunStatus.INTERRUPTED.name(),
                now,
                now,
                AgentRunStatus.CREATED.name(),
                AgentRunStatus.RUNNING.name());
    }
}
