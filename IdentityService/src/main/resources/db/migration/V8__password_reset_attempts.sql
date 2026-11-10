ALTER TABLE dbo.auth_action_token
    ADD failed_attempts INT NOT NULL CONSTRAINT DF_auth_action_token_failed_attempts DEFAULT 0;
GO

-- SQL Server must compile the CHECK after the new column exists.
ALTER TABLE dbo.auth_action_token
    ADD CONSTRAINT CK_auth_action_token_failed_attempts CHECK (failed_attempts >= 0);
