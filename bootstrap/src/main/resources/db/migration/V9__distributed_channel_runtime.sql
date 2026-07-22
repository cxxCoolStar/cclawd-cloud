ALTER TABLE channel_conversations
    ADD COLUMN next_sequence BIGINT NOT NULL DEFAULT 0;

CREATE TABLE channel_message_inbox (
    id VARCHAR(64) PRIMARY KEY,
    binding_id VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(64) NOT NULL,
    external_message_id VARCHAR(255) NOT NULL,
    sequence_no BIGINT NOT NULL,
    text TEXT NOT NULL,
    context_token TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempts INTEGER NOT NULL,
    available_at BIGINT NOT NULL,
    claimed_by VARCHAR(128),
    claim_expires_at BIGINT,
    run_id VARCHAR(64),
    last_error TEXT,
    published_at BIGINT,
    completed_at BIGINT,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    UNIQUE (binding_id, external_message_id),
    UNIQUE (conversation_id, sequence_no),
    FOREIGN KEY (binding_id) REFERENCES channel_bindings(id) ON DELETE CASCADE,
    FOREIGN KEY (conversation_id) REFERENCES channel_conversations(id) ON DELETE CASCADE
);

CREATE TABLE channel_message_outbox (
    id VARCHAR(64) PRIMARY KEY,
    inbox_id VARCHAR(64) NOT NULL,
    binding_id VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    sequence_no BIGINT NOT NULL,
    chat_id VARCHAR(512) NOT NULL,
    text TEXT NOT NULL,
    context_token TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempts INTEGER NOT NULL,
    available_at BIGINT NOT NULL,
    claimed_by VARCHAR(128),
    claim_expires_at BIGINT,
    provider_message_id VARCHAR(255),
    last_error TEXT,
    published_at BIGINT,
    sent_at BIGINT,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    UNIQUE (run_id, sequence_no),
    FOREIGN KEY (inbox_id) REFERENCES channel_message_inbox(id) ON DELETE CASCADE,
    FOREIGN KEY (binding_id) REFERENCES channel_bindings(id) ON DELETE CASCADE,
    FOREIGN KEY (conversation_id) REFERENCES channel_conversations(id) ON DELETE CASCADE
);

CREATE INDEX idx_channel_inbox_dispatch
    ON channel_message_inbox (status, available_at, created_at);

CREATE INDEX idx_channel_inbox_conversation
    ON channel_message_inbox (conversation_id, sequence_no, status);

CREATE INDEX idx_channel_inbox_claim
    ON channel_message_inbox (status, claim_expires_at);

CREATE INDEX idx_channel_outbox_dispatch
    ON channel_message_outbox (status, available_at, created_at);

CREATE INDEX idx_channel_outbox_conversation
    ON channel_message_outbox (conversation_id, sequence_no, status);

CREATE INDEX idx_channel_outbox_claim
    ON channel_message_outbox (status, claim_expires_at);
