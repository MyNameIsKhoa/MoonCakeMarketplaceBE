CREATE TABLE dbo.payout_account (
    payout_account_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_payout_account PRIMARY KEY,
    user_id BIGINT NOT NULL,
    owner_kind VARCHAR(10) NOT NULL,
    bank_bin VARCHAR(6) NOT NULL,
    bank_name NVARCHAR(100) NOT NULL,
    account_number VARCHAR(19) NOT NULL,
    account_holder NVARCHAR(100) NOT NULL,
    updated_at DATETIME2(0) NOT NULL CONSTRAINT DF_payout_account_updated_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT FK_payout_account_user FOREIGN KEY (user_id) REFERENCES dbo.app_user(user_id),
    CONSTRAINT UQ_payout_account_user_kind UNIQUE (user_id, owner_kind),
    CONSTRAINT CK_payout_account_kind CHECK (owner_kind IN ('CUSTOMER', 'SUPPLIER'))
);
