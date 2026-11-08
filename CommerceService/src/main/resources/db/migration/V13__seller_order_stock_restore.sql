-- A cancelled retail seller order returns exactly its own lines to Catalog stock (per-line restore), even while
-- the other seller orders of the checkout go on. Stamped once Catalog confirmed; the recovery job retries until then.
ALTER TABLE dbo.seller_order ADD stock_restored_at DATETIME2(0) NULL;
GO
-- Seller orders cancelled before this migration followed the whole-checkout rule (restored with their checkout, or
-- by an operator by hand while the other seller orders were delivered): never restore their lines again.
UPDATE dbo.seller_order SET stock_restored_at = SYSUTCDATETIME() WHERE status = 'CANCELLED';
