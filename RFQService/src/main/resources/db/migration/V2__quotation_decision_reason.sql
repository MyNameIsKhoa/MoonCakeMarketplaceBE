-- Store quotation decisions and durable create/revision request keys.
ALTER TABLE dbo.quotation ADD decision_reason NVARCHAR(2000) NULL;

CREATE TABLE dbo.quotation_command (
    id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_quotation_command PRIMARY KEY,
    actor_user_id_ref BIGINT NOT NULL,
    command_type NVARCHAR(20) NOT NULL,
    target_id BIGINT NOT NULL,
    request_key NVARCHAR(100) COLLATE Latin1_General_100_BIN2 NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    quotation_id BIGINT NOT NULL,
    CONSTRAINT UQ_quotation_command_request UNIQUE (actor_user_id_ref, command_type, target_id, request_key),
    CONSTRAINT CK_quotation_command_type CHECK (command_type IN ('CREATE','REVISE')),
    CONSTRAINT FK_quotation_command_quotation FOREIGN KEY (quotation_id) REFERENCES dbo.quotation(quotation_id)
);
GO
