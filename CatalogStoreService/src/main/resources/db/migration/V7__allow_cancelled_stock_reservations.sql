-- Cancellation restores confirmed stock, so retain the terminal state for idempotent retries.
ALTER TABLE dbo.stock_reservation
    DROP CONSTRAINT CK_stock_reservation_status;

ALTER TABLE dbo.stock_reservation
    ADD CONSTRAINT CK_stock_reservation_status
    CHECK (status IN ('HELD', 'CONFIRMED', 'RELEASED', 'CANCELLED'));
