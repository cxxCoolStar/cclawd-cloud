CREATE TABLE users (
    id VARCHAR(64) PRIMARY KEY,
    username VARCHAR(100) NOT NULL UNIQUE,
    email VARCHAR(255) NOT NULL,
    role VARCHAR(40) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    status VARCHAR(40) NOT NULL,
    created_at BIGINT NOT NULL
);

CREATE TABLE providers (
    id VARCHAR(64) PRIMARY KEY,
    provider_type VARCHAR(40) NOT NULL,
    name VARCHAR(100) NOT NULL,
    api_base VARCHAR(1000) NOT NULL,
    api_key TEXT NOT NULL,
    model VARCHAR(255) NOT NULL,
    temperature DOUBLE PRECISION NOT NULL,
    max_tokens INTEGER NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

CREATE TABLE agents (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    provider_id VARCHAR(64) NOT NULL,
    model VARCHAR(255) NOT NULL,
    system_prompt TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id),
    FOREIGN KEY (provider_id) REFERENCES providers(id)
);

CREATE TABLE configs (
    config_key VARCHAR(255) PRIMARY KEY,
    config_value TEXT NOT NULL,
    updated_at BIGINT NOT NULL
);

CREATE TABLE sessions (
    id VARCHAR(128) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    agent_id VARCHAR(64) NOT NULL,
    title VARCHAR(255) NOT NULL,
    preview TEXT NOT NULL,
    channel VARCHAR(40) NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (user_id, agent_id, id),
    FOREIGN KEY (user_id) REFERENCES users(id),
    FOREIGN KEY (agent_id) REFERENCES agents(id)
);

CREATE TABLE session_messages (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    agent_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(128) NOT NULL,
    seq BIGINT NOT NULL,
    role VARCHAR(40) NOT NULL,
    content TEXT NOT NULL,
    provider VARCHAR(40) NOT NULL,
    model VARCHAR(255) NOT NULL,
    created_at BIGINT NOT NULL,
    UNIQUE (user_id, agent_id, session_id, seq)
);

CREATE TABLE session_events (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    agent_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(128) NOT NULL,
    seq BIGINT NOT NULL,
    event_type VARCHAR(60) NOT NULL,
    event_data TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    UNIQUE (user_id, agent_id, session_id, seq)
);

CREATE INDEX idx_sessions_recent
    ON sessions (user_id, agent_id, updated_at);

CREATE INDEX idx_session_messages_history
    ON session_messages (user_id, agent_id, session_id, seq);

CREATE INDEX idx_session_events_replay
    ON session_events (user_id, agent_id, session_id, seq);
