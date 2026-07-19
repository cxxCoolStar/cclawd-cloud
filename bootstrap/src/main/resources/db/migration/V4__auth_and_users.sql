-- V4: 认证与用户管理（OPENAGENT_JAVA_V9_PLAN.md 3.1 节 M1）
-- users 表补齐登录所需列；新建 auth_sessions 会话表（cookie token 认证）

-- 4.1 users 表补齐认证资料列
-- password_hash 为空串表示该用户未设密码（如种子 local-user），不可登录
ALTER TABLE users ADD COLUMN password_hash TEXT NOT NULL DEFAULT '';
ALTER TABLE users ADD COLUMN avatar_url VARCHAR(500) NOT NULL DEFAULT '';
ALTER TABLE users ADD COLUMN agent_quota INTEGER NOT NULL DEFAULT -1;

-- 4.2 登录会话：token 为主键，SQLite 单机存储，重启不失效
CREATE TABLE auth_sessions (
    token VARCHAR(128) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    created_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE INDEX idx_auth_sessions_user
    ON auth_sessions (user_id, expires_at);
