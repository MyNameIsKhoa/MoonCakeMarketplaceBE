ALTER TABLE dbo.cart_item ADD selected BIT NOT NULL CONSTRAINT DF_cart_item_selected DEFAULT 0;
ALTER TABLE dbo.checkout_attempt ADD selection_request NVARCHAR(MAX) NULL;
DROP INDEX UX_checkout_attempt_live_cart ON dbo.checkout_attempt;
CREATE UNIQUE INDEX UX_checkout_attempt_live_cart ON dbo.checkout_attempt(cart_id)
WHERE status IN ('CREATED','PENDING_STOCK');
