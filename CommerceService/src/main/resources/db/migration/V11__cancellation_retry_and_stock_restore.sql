-- A rejected cancellation no longer blocks a new request: only one REQUESTED/APPROVED case per seller order.
ALTER TABLE dbo.cancellation DROP CONSTRAINT UQ_cancellation_seller_order;
GO
CREATE UNIQUE INDEX UX_cancellation_open_seller_order ON dbo.cancellation(seller_order_id)
    WHERE status IN ('REQUESTED','APPROVED');
-- Catalog restores a retail checkout's confirmed stock once the whole checkout is cancelled.
ALTER TABLE dbo.customer_order ADD stock_restored_at DATETIME2(0) NULL;
GO
-- Orders cancelled before this migration were restored by the unpaid-cancellation flow (or never reserved).
UPDATE dbo.customer_order SET stock_restored_at = SYSUTCDATETIME()
WHERE source_type = 'RETAIL' AND status = 'CANCELLED';
