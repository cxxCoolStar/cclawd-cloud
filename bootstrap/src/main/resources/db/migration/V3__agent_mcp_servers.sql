-- V6 MCP：Agent 级 MCP Server 配置（形状对齐前端 MCPServerConfig：
-- {type: http|stdio, url?, headers?, command?, args?, env?}）
CREATE TABLE IF NOT EXISTS agent_mcp_servers (
    agent_id     TEXT NOT NULL,
    name         TEXT NOT NULL,
    type         TEXT NOT NULL,
    url          TEXT NOT NULL DEFAULT '',
    headers_json TEXT NOT NULL DEFAULT '{}',
    command      TEXT NOT NULL DEFAULT '',
    args_json    TEXT NOT NULL DEFAULT '[]',
    env_json     TEXT NOT NULL DEFAULT '{}',
    created_at   INTEGER NOT NULL,
    updated_at   INTEGER NOT NULL,
    PRIMARY KEY (agent_id, name)
);

CREATE INDEX IF NOT EXISTS idx_agent_mcp_servers_agent ON agent_mcp_servers (agent_id);
