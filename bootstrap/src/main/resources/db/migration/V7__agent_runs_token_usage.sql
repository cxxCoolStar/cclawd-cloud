-- V7: agent_runs 增加 token 用量四列（EVALUATION_PLAN.md Phase 1.1）
-- 独立列而非 JSON，便于 SQL 聚合分析；AFTER_MODEL_CALL hook 逐次增量累加。
-- cache_write_tokens 当前 OpenAI 兼容层恒为 0，为 Anthropic 原生缓存预留。

ALTER TABLE agent_runs ADD COLUMN input_tokens BIGINT NOT NULL DEFAULT 0;
ALTER TABLE agent_runs ADD COLUMN output_tokens BIGINT NOT NULL DEFAULT 0;
ALTER TABLE agent_runs ADD COLUMN cache_read_tokens BIGINT NOT NULL DEFAULT 0;
ALTER TABLE agent_runs ADD COLUMN cache_write_tokens BIGINT NOT NULL DEFAULT 0;
