ALTER TABLE dbo.store ADD shipping_fee DECIMAL(18,2) NULL;
GO
ALTER TABLE dbo.store ADD CONSTRAINT CK_store_shipping_fee CHECK (shipping_fee >= 0);
