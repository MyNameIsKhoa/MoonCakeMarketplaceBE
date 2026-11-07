ALTER TABLE dbo.customer_order ADD stock_reservation_key VARCHAR(100) NULL;
GO
UPDATE o SET stock_reservation_key = a.reservation_key
FROM dbo.customer_order o
JOIN dbo.checkout_attempt a ON a.order_id = o.order_id AND a.status = 'COMPLETED';
