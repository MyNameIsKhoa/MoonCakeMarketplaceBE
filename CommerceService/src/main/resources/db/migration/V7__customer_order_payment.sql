-- One provider checkout for one retail customer order; seller allocations stay in payment.
CREATE TABLE customer_payment (
    customer_payment_id BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    customer_order_id BIGINT NOT NULL,
    order_code BIGINT NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    amount BIGINT NOT NULL CHECK (amount > 0),
    status VARCHAR(20) NOT NULL,
    payment_link_id VARCHAR(100) NULL,
    checkout_url NVARCHAR(1000) NULL,
    qr_code NVARCHAR(2000) NULL,
    expires_at DATETIME2(0) NOT NULL,
    transaction_ref VARCHAR(255) NULL,
    paid_at DATETIME2(0) NULL,
    CONSTRAINT FK_customer_payment_order FOREIGN KEY (customer_order_id) REFERENCES customer_order(order_id),
    CONSTRAINT UQ_customer_payment_code UNIQUE (order_code),
    CONSTRAINT UQ_customer_payment_key UNIQUE (idempotency_key)
);
CREATE INDEX IX_customer_payment_order ON customer_payment(customer_order_id, customer_payment_id);
CREATE TABLE customer_payment_item (
    customer_payment_id BIGINT NOT NULL,
    payment_id BIGINT NOT NULL,
    amount BIGINT NOT NULL CHECK (amount > 0),
    CONSTRAINT PK_customer_payment_item PRIMARY KEY (customer_payment_id, payment_id),
    CONSTRAINT FK_customer_payment_item_parent FOREIGN KEY (customer_payment_id) REFERENCES customer_payment(customer_payment_id),
    CONSTRAINT FK_customer_payment_item_payment FOREIGN KEY (payment_id) REFERENCES payment(payment_id)
);
CREATE TABLE customer_payment_webhook_inbox (
    provider_reference VARCHAR(255) NOT NULL PRIMARY KEY,
    customer_payment_id BIGINT NOT NULL,
    amount BIGINT NOT NULL,
    received_at DATETIME2(0) NOT NULL,
    CONSTRAINT FK_customer_payment_inbox_payment FOREIGN KEY (customer_payment_id) REFERENCES customer_payment(customer_payment_id)
);
