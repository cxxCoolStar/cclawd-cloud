-- V5: API Key（OPENAGENT_JAVA_V9_PLAN.md 3.2 节 M2）
-- key 明文只在创建响应返回一次，库中只存 SHA-256 散列；
-- agent_ids 为 JSON 数组，空数组 = 不限制（可访问属主全部 agent）

CREATE TABLE api_keys (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    key_hash VARCHAR(128) NOT NULL,
    agent_ids TEXT NOT NULL DEFAULT '[]',
    created_at BIGINT NOT NULL,
    last_used_at BIGINT,
    FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE UNIQUE INDEX idx_api_keys_hash
    ON api_keys (key_hash);

CREATE INDEX idx_api_keys_user
    ON api_keys (user_id);
