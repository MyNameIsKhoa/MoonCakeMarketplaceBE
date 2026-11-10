-- Freeze the beneficiary when an operator prepares a transfer. Existing pending rows
-- stay null until their first prepare-transfer request; completed history is unchanged.
ALTER TABLE dbo.refund ADD bank_bin VARCHAR(6) NULL, bank_name NVARCHAR(100) NULL,
    bank_account_number VARCHAR(19) NULL, bank_account_holder NVARCHAR(100) NULL,
    bank_snapshot_at DATETIME2(0) NULL;

ALTER TABLE dbo.settlement ADD bank_bin VARCHAR(6) NULL, bank_name NVARCHAR(100) NULL,
    bank_account_number VARCHAR(19) NULL, bank_account_holder NVARCHAR(100) NULL,
    bank_snapshot_at DATETIME2(0) NULL;
