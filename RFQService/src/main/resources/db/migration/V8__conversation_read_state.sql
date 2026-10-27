-- How far each participant has read a conversation; no row means nothing read yet.
CREATE TABLE dbo.conversation_read_state (
    conversation_read_state_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_conversation_read_state PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    user_id_ref BIGINT NOT NULL,
    last_read_message_id BIGINT NOT NULL,
    updated_at DATETIME2(0) NOT NULL CONSTRAINT DF_conversation_read_state_updated_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT UQ_conversation_read_state UNIQUE (conversation_id, user_id_ref),
    CONSTRAINT FK_conversation_read_state_conversation FOREIGN KEY (conversation_id) REFERENCES dbo.conversation(conversation_id)
);
