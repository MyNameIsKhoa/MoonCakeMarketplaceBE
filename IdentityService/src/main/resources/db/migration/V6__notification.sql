-- In-app notifications, one row per recipient user. Other services create them through
-- POST /internal/v1/notifications; users read their own through /api/notifications.
CREATE TABLE dbo.notification (
    notification_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_notification PRIMARY KEY,
    user_id_ref BIGINT NOT NULL,
    notification_type NVARCHAR(50) NOT NULL,
    title NVARCHAR(255) NOT NULL,
    body NVARCHAR(1000) NULL,
    -- Small JSON object the FE routes on, e.g. {"kind":"ORDER","id":"501"}.
    target_json NVARCHAR(1000) NULL,
    -- Caller-chosen idempotency key; unique per recipient so retries never duplicate.
    dedup_key NVARCHAR(150) NULL,
    created_at DATETIME2(0) NOT NULL CONSTRAINT DF_notification_created_at DEFAULT SYSUTCDATETIME(),
    read_at DATETIME2(0) NULL,
    CONSTRAINT FK_notification_user FOREIGN KEY (user_id_ref) REFERENCES dbo.app_user(user_id)
);

-- Inbox page, newest first.
CREATE INDEX IX_notification_user_created
    ON dbo.notification(user_id_ref, created_at DESC, notification_id DESC);

-- Unread badge count and "mark all read".
CREATE INDEX IX_notification_user_unread
    ON dbo.notification(user_id_ref) WHERE read_at IS NULL;

CREATE UNIQUE INDEX UQ_notification_user_dedup
    ON dbo.notification(user_id_ref, dedup_key) WHERE dedup_key IS NOT NULL;
