-- V2: Agent 运行、工具执行与工具配置（OPENAGENT_JAVA_V2_PLAN.md 第 9 章）
-- 增量迁移，不改动 V1 已有表的主键与 seq 语义

-- 9.1 Agent 运行记录：一次用户消息触发的完整 Agent 执行
CREATE TABLE agent_runs (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    agent_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(128) NOT NULL,
    status VARCHAR(40) NOT NULL,
    tool_iterations INT NOT NULL DEFAULT 0,
    error_code VARCHAR(60),
    error_message TEXT,
    started_at BIGINT NOT NULL,
    completed_at BIGINT,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

CREATE INDEX idx_agent_runs_session
    ON agent_runs (user_id, agent_id, session_id, created_at);

CREATE INDEX idx_agent_runs_status
    ON agent_runs (status, updated_at);

-- 9.2 工具执行记录：run 内每次工具调用的请求、结果与状态
CREATE TABLE tool_executions (
    id VARCHAR(64) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    tool_call_id VARCHAR(128) NOT NULL,
    sequence INT NOT NULL,
    tool_name VARCHAR(100) NOT NULL,
    arguments_json TEXT NOT NULL DEFAULT '',
    status VARCHAR(40) NOT NULL,
    result_content TEXT,
    error_code VARCHAR(60),
    error_message TEXT,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL,
    completed_at BIGINT,
    UNIQUE (run_id, tool_call_id)
);

CREATE INDEX idx_tool_executions_run
    ON tool_executions (run_id, sequence);

-- 9.3 Agent 工具配置：按 (agent, tool) 启停与非敏感配置
CREATE TABLE agent_tools (
    agent_id VARCHAR(64) NOT NULL,
    tool_name VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    config_json TEXT NOT NULL DEFAULT '{}',
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (agent_id, tool_name)
);

-- 9.4 会话消息扩展：支持 role=tool 消息与 assistant tool_calls 元数据
ALTER TABLE session_messages ADD COLUMN tool_call_id VARCHAR(128) NOT NULL DEFAULT '';
ALTER TABLE session_messages ADD COLUMN tool_name VARCHAR(100) NOT NULL DEFAULT '';
ALTER TABLE session_messages ADD COLUMN metadata_json TEXT NOT NULL DEFAULT '';
