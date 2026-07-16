package ai.openagent.bootstrap.persistence;

import ai.openagent.bootstrap.agentrun.ToolExecutionStatus;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 工具执行仓储
 *
 * <p>
 * (run_id, tool_call_id) 唯一约束保证每个 tool call 只有一条执行记录，
 * 支撑「每个 assistant tool call 必须有对应 tool result」的协议闭合校验
 * </p>
 */
@Repository
@RequiredArgsConstructor
public class ToolExecutionRepository {

    private static final String SELECT_COLUMNS =
            "SELECT id, run_id, tool_call_id, sequence, tool_name, arguments_json, status, result_content, error_code, error_message, duration_ms, created_at, completed_at FROM tool_executions";

    private static final RowMapper<ToolExecutionRecord> ROW_MAPPER = (rs, row) -> new ToolExecutionRecord(
            rs.getString("id"),
            rs.getString("run_id"),
            rs.getString("tool_call_id"),
            rs.getInt("sequence"),
            rs.getString("tool_name"),
            rs.getString("arguments_json"),
            ToolExecutionStatus.valueOf(rs.getString("status")),
            rs.getString("result_content"),
            rs.getString("error_code"),
            rs.getString("error_message"),
            rs.getLong("duration_ms"),
            rs.getLong("created_at"),
            (Long) rs.getObject("completed_at"));

    private final JdbcTemplate jdbc;

    /**
     * 登记一次工具执行请求（sequence 在 run 内原子分配，从 1 开始）
     */
    public void insertRequested(String id, String runId, String toolCallId, String toolName, String argumentsJson) {
        jdbc.update(
                """
                INSERT INTO tool_executions (id, run_id, tool_call_id, sequence, tool_name, arguments_json, status, duration_ms, created_at)
                SELECT ?, ?, ?, COALESCE(MAX(sequence), 0) + 1, ?, ?, ?, 0, ?
                  FROM tool_executions
                 WHERE run_id = ?
                """,
                id,
                runId,
                toolCallId,
                toolName,
                argumentsJson,
                ToolExecutionStatus.REQUESTED.name(),
                System.currentTimeMillis(),
                runId);
    }

    /**
     * 标记执行开始
     */
    public void markRunning(String id) {
        jdbc.update(
                "UPDATE tool_executions SET status = ? WHERE id = ?",
                ToolExecutionStatus.RUNNING.name(),
                id);
    }

    /**
     * 以终态完结执行并写入结果
     */
    public void complete(
            String id,
            ToolExecutionStatus status,
            String resultContent,
            String errorCode,
            String errorMessage,
            long durationMs) {
        jdbc.update(
                "UPDATE tool_executions SET status = ?, result_content = ?, error_code = ?, error_message = ?, duration_ms = ?, completed_at = ? WHERE id = ?",
                status.name(),
                resultContent,
                errorCode,
                errorMessage,
                durationMs,
                System.currentTimeMillis(),
                id);
    }

    /**
     * 按 run 查询全部执行记录（按执行顺序升序）
     */
    public List<ToolExecutionRecord> listByRun(String runId) {
        return jdbc.query(SELECT_COLUMNS + " WHERE run_id = ? ORDER BY sequence", ROW_MAPPER, runId);
    }

    /**
     * 按 (run, toolCallId) 查询执行记录
     */
    public Optional<ToolExecutionRecord> findByToolCallId(String runId, String toolCallId) {
        return jdbc.query(SELECT_COLUMNS + " WHERE run_id = ? AND tool_call_id = ?", ROW_MAPPER, runId, toolCallId)
                .stream()
                .findFirst();
    }
}
