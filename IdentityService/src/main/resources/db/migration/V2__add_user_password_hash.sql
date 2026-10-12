-- MSS Identity Service - Migration V2
-- Add password_hash column to support native authentication

IF NOT EXISTS (
    SELECT 1 FROM sys.columns 
    WHERE object_id = OBJECT_ID('dbo.app_user') AND name = 'password_hash'
)
BEGIN
    ALTER TABLE dbo.app_user ADD password_hash NVARCHAR(255) NULL;
END
GO
