-- Durable boundary between Commerce and Catalog; never reuse a cart for two successful checkouts.
DROP INDEX UX_cart_active_customer ON dbo.cart;
CREATE UNIQUE INDEX UX_cart_open_customer ON dbo.cart(customer_id_ref)
WHERE status IN ('ACTIVE','CHECKOUT_PENDING');
GO
CREATE TABLE dbo.checkout_attempt (
    attempt_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_checkout_attempt PRIMARY KEY,
    customer_id_ref BIGINT NOT NULL,
    cart_id BIGINT NOT NULL,
    address_id_ref BIGINT NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    cart_fingerprint CHAR(64) NOT NULL,
    reservation_key VARCHAR(100) NOT NULL,
    reservation_expires_at DATETIME2(0) NOT NULL,
    status VARCHAR(30) NOT NULL,
    order_id BIGINT NULL,
    created_at DATETIME2(0) NOT NULL CONSTRAINT DF_checkout_attempt_created DEFAULT SYSUTCDATETIME(),
    updated_at DATETIME2(0) NOT NULL CONSTRAINT DF_checkout_attempt_updated DEFAULT SYSUTCDATETIME(),
    CONSTRAINT UQ_checkout_attempt_key UNIQUE (customer_id_ref, idempotency_key),
    CONSTRAINT UQ_checkout_attempt_reservation UNIQUE (reservation_key),
    CONSTRAINT FK_checkout_attempt_cart FOREIGN KEY (cart_id) REFERENCES dbo.cart(cart_id),
    CONSTRAINT FK_checkout_attempt_order FOREIGN KEY (order_id) REFERENCES dbo.customer_order(order_id),
    CONSTRAINT CK_checkout_attempt_status CHECK (status IN ('CREATED','PENDING_STOCK','COMPLETED','FAILED'))
);
GO
CREATE UNIQUE INDEX UX_checkout_attempt_live_cart ON dbo.checkout_attempt(cart_id)
WHERE status IN ('CREATED','PENDING_STOCK','COMPLETED');
GO
CREATE INDEX IX_checkout_attempt_pending ON dbo.checkout_attempt(status, updated_at)
WHERE status = 'PENDING_STOCK';
