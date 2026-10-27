-- TV3/TV6 share Commerce's local transaction boundary; no cross-service DB access.
CREATE TABLE finance_context (
    seller_order_id BIGINT NOT NULL PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    supplier_id BIGINT NOT NULL,
    total_amount BIGINT NOT NULL CHECK (total_amount > 0),
    deposit_amount BIGINT NULL,
    delivery_date DATE NULL,
    accepted BIT NOT NULL,
    source_version BIGINT NOT NULL,
    fulfillment_version BIGINT NOT NULL DEFAULT 0,
    delivered BIT NOT NULL DEFAULT 0,
    received BIT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT FK_finance_context_order FOREIGN KEY (seller_order_id) REFERENCES seller_order(seller_order_id),
    CONSTRAINT CK_finance_deposit CHECK (deposit_amount IS NULL OR (deposit_amount > 0 AND deposit_amount <= total_amount))
);
ALTER TABLE payment ADD order_code BIGINT NULL, payment_link_id VARCHAR(100) NULL,
    checkout_url NVARCHAR(1000) NULL, qr_code NVARCHAR(2000) NULL,
    idempotency_key VARCHAR(100) NULL, hold_status VARCHAR(20) NULL,
    expires_at DATETIME2(0) NULL, context_version BIGINT NULL;
ALTER TABLE payment ADD CONSTRAINT CK_payment_integer_vnd CHECK (amount = FLOOR(amount));
GO
CREATE UNIQUE INDEX UX_payment_order_code ON payment(order_code) WHERE order_code IS NOT NULL;
CREATE UNIQUE INDEX UX_payment_link_id ON payment(payment_link_id) WHERE payment_link_id IS NOT NULL;
CREATE UNIQUE INDEX UX_payment_idempotency ON payment(idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE SEQUENCE finance_order_code AS BIGINT START WITH 1000000000 INCREMENT BY 1;

ALTER TABLE cancellation ADD requested_at DATETIME2(0) NULL, decision_reason NVARCHAR(1000) NULL;
ALTER TABLE refund ADD idempotency_key VARCHAR(100) NULL, policy_snapshot NVARCHAR(2000) NULL,
    transfer_reference VARCHAR(255) NULL, evidence_url NVARCHAR(1000) NULL;
ALTER TABLE refund ADD CONSTRAINT CK_refund_integer_vnd CHECK (amount = FLOOR(amount));
GO
CREATE UNIQUE INDEX UX_refund_idempotency ON refund(idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE UNIQUE INDEX UX_refund_transfer ON refund(transfer_reference) WHERE transfer_reference IS NOT NULL;
ALTER TABLE settlement ADD idempotency_key VARCHAR(100) NULL, payout_reference VARCHAR(255) NULL,
    evidence_url NVARCHAR(1000) NULL, fee_basis NVARCHAR(500) NULL, rounding_rule VARCHAR(30) NULL;
GO
CREATE UNIQUE INDEX UX_settlement_key ON settlement(idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE UNIQUE INDEX UX_settlement_reference ON settlement(payout_reference) WHERE payout_reference IS NOT NULL;

CREATE TABLE finance_webhook_inbox (
    provider_reference VARCHAR(255) NOT NULL PRIMARY KEY,
    payment_id BIGINT NOT NULL,
    amount BIGINT NOT NULL,
    received_at DATETIME2(0) NOT NULL,
    CONSTRAINT FK_finance_inbox_payment FOREIGN KEY (payment_id) REFERENCES payment(payment_id)
);
CREATE TABLE finance_provider_probe (
    provider_reference VARCHAR(255) NOT NULL PRIMARY KEY,
    received_at DATETIME2(0) NOT NULL
);
CREATE TABLE finance_command (
    command_key VARCHAR(100) NOT NULL PRIMARY KEY,
    fingerprint VARCHAR(64) NOT NULL,
    seller_order_id BIGINT NOT NULL
);
CREATE TABLE finance_audit (
    audit_id BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    seller_order_id BIGINT NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    resource_id BIGINT NOT NULL,
    actor NVARCHAR(255) NOT NULL,
    detail NVARCHAR(2000) NOT NULL,
    created_at DATETIME2(0) NOT NULL
);
CREATE INDEX IX_finance_audit_order ON finance_audit(seller_order_id, audit_id);
CREATE TABLE finance_ledger (
    ledger_id BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    seller_order_id BIGINT NOT NULL,
    entry_key VARCHAR(100) NOT NULL UNIQUE,
    entry_type VARCHAR(30) NOT NULL,
    amount BIGINT NOT NULL CHECK (amount > 0),
    created_at DATETIME2(0) NOT NULL
);
CREATE TABLE finance_outbox (
    event_id VARCHAR(36) NOT NULL PRIMARY KEY,
    seller_order_id BIGINT NOT NULL,
    aggregate_version BIGINT NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    resource_id BIGINT NOT NULL,
    created_at DATETIME2(0) NOT NULL,
    published_at DATETIME2(0) NULL,
    CONSTRAINT UQ_finance_event_version UNIQUE(seller_order_id, aggregate_version)
);
