-- Idempotency keys are opaque: SQL Server's default case-insensitive collation must not merge intents.
ALTER TABLE dbo.quotation_conversion DROP CONSTRAINT UQ_quotation_conversion_key;
ALTER TABLE dbo.quotation_conversion ALTER COLUMN idempotency_key NVARCHAR(100) COLLATE Latin1_General_100_BIN2 NOT NULL;
ALTER TABLE dbo.quotation_conversion ADD CONSTRAINT UQ_quotation_conversion_key UNIQUE (customer_id_ref, idempotency_key);
