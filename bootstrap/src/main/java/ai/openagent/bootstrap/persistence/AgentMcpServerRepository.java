package ai.openagent.bootstrap.persistence;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agent MCP Server 配置仓储（agent_mcp_servers）
 */
@Repository
@RequiredArgsConstructor
public class AgentMcpServerRepository {

    private static final String SELECT_COLUMNS =
            "SELECT agent_id, name, type, url, headers_json, command, args_json, env_json, created_at, updated_at"
                    + " FROM agent_mcp_servers";

    private static final RowMapper<AgentMcpServerRecord> ROW_MAPPER = (rs, row) -> new AgentMcpServerRecord(
            rs.getString("agent_id"),
            rs.getString("name"),
            rs.getString("type"),
            rs.getString("url"),
            rs.getString("headers_json"),
            rs.getString("command"),
            rs.getString("args_json"),
            rs.getString("env_json"),
            rs.getLong("created_at"),
            rs.getLong("updated_at"));

    private final JdbcTemplate jdbc;

    /**
     * 查询 Agent 的全部 MCP Server 配置
     */
    public List<AgentMcpServerRecord> listByAgent(String agentId) {
        return jdbc.query(SELECT_COLUMNS + " WHERE agent_id = ? ORDER BY name", ROW_MAPPER, agentId);
    }

    /**
     * 整表替换 Agent 的 MCP Server 配置（前端 updateAgent mcpServers 语义）
     */
    @Transactional
    public void replaceAll(String agentId, List<AgentMcpServerRecord> servers) {
        jdbc.update("DELETE FROM agent_mcp_servers WHERE agent_id = ?", agentId);
        long now = System.currentTimeMillis();
        for (AgentMcpServerRecord server : servers) {
            jdbc.update(
                    "INSERT INTO agent_mcp_servers"
                            + " (agent_id, name, type, url, headers_json, command, args_json, env_json, created_at, updated_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    agentId,
                    server.name(),
                    server.type(),
                    server.url(),
                    server.headersJson(),
                    server.command(),
                    server.argsJson(),
                    server.envJson(),
                    now,
                    now);
        }
    }
}
