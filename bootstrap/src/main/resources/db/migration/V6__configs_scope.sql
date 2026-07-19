-- V6: 配置三级作用域（OPENAGENT_JAVA_V9_PLAN.md 3.3 节 M3）
-- configs 表加 scope/scope_id 两列，主键改为 (scope, scope_id, config_key)。
-- SQLite 不支持修改主键，按既有风格重建表迁移。
--
-- 存量行归位约定：
--   skills.agentEntries.{agentId} 为 agent 级配置 → scope='agent'，scope_id=agentId
--   其余键（agents.defaults / skills.entries / prefs / sandbox / admin.registrationOpen
--   等平台级配置）→ scope='system'，scope_id=''（空串为 system 的统一约定）

CREATE TABLE configs_new (
    scope VARCHAR(20) NOT NULL,
    scope_id VARCHAR(128) NOT NULL,
    config_key VARCHAR(255) NOT NULL,
    config_value TEXT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (scope, scope_id, config_key)
);

INSERT INTO configs_new (scope, scope_id, config_key, config_value, updated_at)
SELECT CASE WHEN config_key LIKE 'skills.agentEntries.%' THEN 'agent' ELSE 'system' END,
       CASE WHEN config_key LIKE 'skills.agentEntries.%'
            THEN substr(config_key, length('skills.agentEntries.') + 1)
            ELSE '' END,
       config_key,
       config_value,
       updated_at
FROM configs;

DROP TABLE configs;

ALTER TABLE configs_new RENAME TO configs;

CREATE INDEX idx_configs_key
    ON configs (config_key);
