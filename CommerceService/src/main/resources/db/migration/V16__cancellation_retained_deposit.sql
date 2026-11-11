-- Deposit kept for the supplier when the buyer cancels after preparation started or misses the balance deadline.
-- The cancellation needs only the rest of the collected money refunded; settlement pays the kept part to the supplier.
ALTER TABLE dbo.cancellation ADD retained_amount BIGINT CONSTRAINT DF_cancellation_retained_amount DEFAULT 0 NOT NULL;
