CREATE TABLE dbo.google_login_otp (
    email NVARCHAR(255) NOT NULL CONSTRAINT PK_google_login_otp PRIMARY KEY,
    otp_hash CHAR(64) NOT NULL,
    expires_at DATETIME2(0) NOT NULL,
    created_at DATETIME2(0) NOT NULL
);
