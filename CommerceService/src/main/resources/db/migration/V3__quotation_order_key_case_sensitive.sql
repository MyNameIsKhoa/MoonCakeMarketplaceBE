ALTER TABLE dbo.quotation_order_conversion DROP CONSTRAINT UQ_quotation_order_conversion_key;
ALTER TABLE dbo.quotation_order_conversion ALTER COLUMN idempotency_key NVARCHAR(100) COLLATE Latin1_General_100_BIN2 NOT NULL;
ALTER TABLE dbo.quotation_order_conversion ADD CONSTRAINT UQ_quotation_order_conversion_key UNIQUE (idempotency_key);
