package ai.openagent.bootstrap.persistence;

import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Agent 工具配置仓储
 */
@Repository
@RequiredArgsConstructor
public class AgentToolRepository {

    private static final String SELECT_COLUMNS =
            "SELECT agent_id, tool_name, enabled, config_json, created_at, updated_at FROM agent_tools";

    private static final RowMapper<AgentToolRecord> ROW_MAPPER = (rs, row) -> new AgentToolRecord(
            rs.getString("agent_id"),
            rs.getString("tool_name"),
            rs.getBoolean("enabled"),
            rs.getString("config_json"),
            rs.getLong("created_at"),
            rs.getLong("updated_at"));

    private final JdbcTemplate jdbc;

    /**
     * 查询 Agent 的全部工具配置
     */
    public List<AgentToolRecord> listByAgent(String agentId) {
        return jdbc.query(SELECT_COLUMNS + " WHERE agent_id = ? ORDER BY tool_name", ROW_MAPPER, agentId);
    }

    /**
     * 查询 Agent 已启用的工具名称
     */
    public List<String> listEnabledToolNames(String agentId) {
        return jdbc.queryForList(
                "SELECT tool_name FROM agent_tools WHERE agent_id = ? AND enabled = TRUE ORDER BY tool_name",
                String.class,
                agentId);
    }

    /**
     * 按 (agent, tool) 查询配置
     */
    public Optional<AgentToolRecord> find(String agentId, String toolName) {
        return jdbc.query(SELECT_COLUMNS + " WHERE agent_id = ? AND tool_name = ?", ROW_MAPPER, agentId, toolName)
                .stream()
                .findFirst();
    }

    /**
     * 写入或更新工具配置（UPDATE-first，避免并发插入冲突）
     */
    public void upsert(String agentId, String toolName, boolean enabled, String configJson) {
        long now = System.currentTimeMillis();
        int updated = jdbc.update(
                "UPDATE agent_tools SET enabled = ?, config_json = ?, updated_at = ? WHERE agent_id = ? AND tool_name = ?",
                enabled,
                configJson,
                now,
                agentId,
                toolName);
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO agent_tools (agent_id, tool_name, enabled, config_json, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                    agentId,
                    toolName,
                    enabled,
                    configJson,
                    now,
                    now);
        }
    }

    /**
     * 判断 (agent, tool) 配置是否存在
     */
    public boolean exists(String agentId, String toolName) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_tools WHERE agent_id = ? AND tool_name = ?",
                Integer.class,
                agentId,
                toolName);
        return count != null && count > 0;
    }
}
