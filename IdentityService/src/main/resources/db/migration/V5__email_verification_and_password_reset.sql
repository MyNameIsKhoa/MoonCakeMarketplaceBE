IF COL_LENGTH('dbo.app_user', 'email_verified') IS NULL
BEGIN
    ALTER TABLE dbo.app_user
        ADD email_verified BIT NOT NULL CONSTRAINT DF_app_user_email_verified DEFAULT 1;
END
GO

CREATE TABLE dbo.auth_action_token (
    auth_action_token_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_auth_action_token PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token_hash CHAR(64) NOT NULL,
    purpose NVARCHAR(30) NOT NULL,
    expires_at DATETIME2(0) NOT NULL,
    used_at DATETIME2(0) NULL,
    created_at DATETIME2(0) NOT NULL CONSTRAINT DF_auth_action_token_created_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT UQ_auth_action_token_hash UNIQUE (token_hash),
    CONSTRAINT FK_auth_action_token_user FOREIGN KEY (user_id) REFERENCES dbo.app_user(user_id)
);

CREATE INDEX IX_auth_action_token_user_purpose
    ON dbo.auth_action_token(user_id, purpose, expires_at);
