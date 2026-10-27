-- One active cart per customer. Existing duplicates must be resolved before applying this migration.
CREATE UNIQUE INDEX UX_cart_active_customer ON dbo.cart(customer_id_ref) WHERE status = 'ACTIVE';
