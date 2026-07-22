CREATE TABLE channel_bindings (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    agent_id VARCHAR(64) NOT NULL,
    channel_type VARCHAR(40) NOT NULL,
    account_id VARCHAR(255) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    credentials_json TEXT NOT NULL,
    enabled BOOLEAN NOT NULL,
    shared_identity BOOLEAN NOT NULL,
    state_json TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    UNIQUE (channel_type, account_id),
    FOREIGN KEY (user_id) REFERENCES users(id),
    FOREIGN KEY (agent_id) REFERENCES agents(id)
);

CREATE TABLE channel_conversations (
    id VARCHAR(64) PRIMARY KEY,
    binding_id VARCHAR(64) NOT NULL,
    chat_id VARCHAR(512) NOT NULL,
    chatter_id VARCHAR(512) NOT NULL,
    session_id VARCHAR(128) NOT NULL,
    context_token TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    UNIQUE (binding_id, chat_id),
    FOREIGN KEY (binding_id) REFERENCES channel_bindings(id)
);

CREATE TABLE channel_inbound_messages (
    binding_id VARCHAR(64) NOT NULL,
    message_id VARCHAR(255) NOT NULL,
    conversation_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64),
    received_at BIGINT NOT NULL,
    PRIMARY KEY (binding_id, message_id),
    FOREIGN KEY (binding_id) REFERENCES channel_bindings(id),
    FOREIGN KEY (conversation_id) REFERENCES channel_conversations(id)
);

CREATE INDEX idx_channel_bindings_owner
    ON channel_bindings (user_id, agent_id, channel_type);

CREATE INDEX idx_channel_conversations_session
    ON channel_conversations (session_id);
