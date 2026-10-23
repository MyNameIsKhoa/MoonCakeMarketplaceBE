-- Existing messages keep stable IDs and receive a reserved, unique correlation ID.
ALTER TABLE dbo.conversation_message ADD client_message_id NVARCHAR(100) COLLATE Latin1_General_100_BIN2 NULL;
GO
UPDATE dbo.conversation_message
SET client_message_id = CONCAT('legacy:', CONVERT(NVARCHAR(20), message_id));
ALTER TABLE dbo.conversation_message ALTER COLUMN client_message_id NVARCHAR(100) COLLATE Latin1_General_100_BIN2 NOT NULL;
ALTER TABLE dbo.conversation_message ADD CONSTRAINT UQ_message_client_id
    UNIQUE (conversation_id, sender_user_id_ref, client_message_id);
CREATE INDEX IX_conversation_message_cursor ON dbo.conversation_message(conversation_id, message_id);
GO
