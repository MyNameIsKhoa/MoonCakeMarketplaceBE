-- MSS Commerce Service - SQL Server physical schema
-- *_id_ref columns that point to other services are intentionally NOT foreign keys.

CREATE TABLE dbo.cart (
    cart_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_cart PRIMARY KEY,
    customer_id_ref BIGINT NOT NULL,
    status NVARCHAR(30) NOT NULL,
    created_at DATETIME2(0) NOT NULL CONSTRAINT DF_cart_created_at DEFAULT SYSUTCDATETIME(),
    updated_at DATETIME2(0) NOT NULL CONSTRAINT DF_cart_updated_at DEFAULT SYSUTCDATETIME()
);

CREATE INDEX IX_cart_customer_id_ref ON dbo.cart(customer_id_ref);

CREATE TABLE dbo.cart_item (
    cart_item_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_cart_item PRIMARY KEY,
    cart_id BIGINT NOT NULL,
    variant_id_ref BIGINT NOT NULL,
    quantity INT NOT NULL,
    added_at DATETIME2(0) NOT NULL CONSTRAINT DF_cart_item_added_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT UQ_cart_item_variant UNIQUE (cart_id, variant_id_ref),
    CONSTRAINT CK_cart_item_quantity CHECK (quantity > 0),
    CONSTRAINT FK_cart_item_cart FOREIGN KEY (cart_id) REFERENCES dbo.cart(cart_id)
);

CREATE INDEX IX_cart_item_variant_id_ref ON dbo.cart_item(variant_id_ref);

CREATE TABLE dbo.customer_order (
    order_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_customer_order PRIMARY KEY,
    order_no NVARCHAR(100) NOT NULL,
    customer_id_ref BIGINT NOT NULL,
    source_type NVARCHAR(30) NOT NULL,
    rfq_id_ref BIGINT NULL,
    subtotal DECIMAL(18,2) NOT NULL,
    discount_total DECIMAL(18,2) NOT NULL CONSTRAINT DF_customer_order_discount DEFAULT 0,
    shipping_total DECIMAL(18,2) NOT NULL CONSTRAINT DF_customer_order_shipping DEFAULT 0,
    grand_total DECIMAL(18,2) NOT NULL,
    status NVARCHAR(30) NOT NULL,
    placed_at DATETIME2(0) NOT NULL CONSTRAINT DF_customer_order_placed_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT UQ_customer_order_no UNIQUE (order_no),
    CONSTRAINT CK_customer_order_source CHECK (source_type IN ('RETAIL','RFQ')),
    CONSTRAINT CK_customer_order_source_ref CHECK (
        (source_type = 'RETAIL' AND rfq_id_ref IS NULL)
        OR (source_type = 'RFQ' AND rfq_id_ref IS NOT NULL)
    ),
    CONSTRAINT CK_customer_order_amounts CHECK (
        subtotal >= 0 AND discount_total >= 0 AND shipping_total >= 0 AND grand_total >= 0
    )
);

CREATE INDEX IX_customer_order_customer_id_ref ON dbo.customer_order(customer_id_ref);
CREATE INDEX IX_customer_order_rfq_id_ref ON dbo.customer_order(rfq_id_ref);

CREATE TABLE dbo.delivery_address (
    delivery_address_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_delivery_address PRIMARY KEY,
    order_id BIGINT NOT NULL,
    source_address_id_ref BIGINT NULL,
    recipient_name NVARCHAR(255) NOT NULL,
    recipient_phone NVARCHAR(30) NOT NULL,
    address_line NVARCHAR(500) NOT NULL,
    ward NVARCHAR(100) NULL,
    district NVARCHAR(100) NULL,
    city NVARCHAR(100) NOT NULL,
    CONSTRAINT UQ_delivery_address_order UNIQUE (order_id),
    CONSTRAINT FK_delivery_address_order FOREIGN KEY (order_id) REFERENCES dbo.customer_order(order_id)
);

CREATE INDEX IX_delivery_address_source_address_ref ON dbo.delivery_address(source_address_id_ref);

CREATE TABLE dbo.seller_order (
    seller_order_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_seller_order PRIMARY KEY,
    order_id BIGINT NOT NULL,
    seller_order_no NVARCHAR(100) NOT NULL,
    store_id_ref BIGINT NOT NULL,
    supplier_id_ref BIGINT NOT NULL,
    quotation_id_ref BIGINT NULL,
    store_name_snapshot NVARCHAR(255) NOT NULL,
    supplier_name_snapshot NVARCHAR(255) NOT NULL,
    subtotal DECIMAL(18,2) NOT NULL,
    shipping_fee DECIMAL(18,2) NOT NULL CONSTRAINT DF_seller_order_shipping DEFAULT 0,
    total_amount DECIMAL(18,2) NOT NULL,
    deposit_percent DECIMAL(5,2) NULL,
    deposit_amount DECIMAL(18,2) NULL,
    remaining_amount DECIMAL(18,2) NULL,
    remaining_due_at DATETIME2(0) NULL,
    delivery_date_snapshot DATE NULL,
    shipping_method_snapshot NVARCHAR(100) NULL,
    payment_terms_snapshot NVARCHAR(MAX) NULL,
    status NVARCHAR(30) NOT NULL,
    CONSTRAINT UQ_seller_order_no UNIQUE (seller_order_no),
    CONSTRAINT CK_seller_order_amounts CHECK (
        subtotal >= 0 AND shipping_fee >= 0 AND total_amount >= 0
        AND (deposit_percent IS NULL OR (deposit_percent >= 0 AND deposit_percent <= 100))
        AND (deposit_amount IS NULL OR deposit_amount >= 0)
        AND (remaining_amount IS NULL OR remaining_amount >= 0)
    ),
    CONSTRAINT FK_seller_order_order FOREIGN KEY (order_id) REFERENCES dbo.customer_order(order_id)
);

CREATE INDEX IX_seller_order_order_id ON dbo.seller_order(order_id);
CREATE INDEX IX_seller_order_store_id_ref ON dbo.seller_order(store_id_ref);
CREATE INDEX IX_seller_order_supplier_id_ref ON dbo.seller_order(supplier_id_ref);
CREATE INDEX IX_seller_order_quotation_id_ref ON dbo.seller_order(quotation_id_ref);

CREATE TABLE dbo.order_item (
    order_item_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_order_item PRIMARY KEY,
    seller_order_id BIGINT NOT NULL,
    variant_id_ref BIGINT NULL,
    product_code_snapshot NVARCHAR(100) NULL,
    product_name_snapshot NVARCHAR(255) NOT NULL,
    sku_snapshot NVARCHAR(100) NULL,
    variant_name_snapshot NVARCHAR(255) NULL,
    image_url_snapshot NVARCHAR(500) NULL,
    quantity INT NOT NULL,
    unit_price DECIMAL(18,2) NOT NULL,
    discount_amount DECIMAL(18,2) NOT NULL CONSTRAINT DF_order_item_discount DEFAULT 0,
    subtotal DECIMAL(18,2) NOT NULL,
    CONSTRAINT CK_order_item_quantity CHECK (quantity > 0),
    CONSTRAINT CK_order_item_amounts CHECK (unit_price >= 0 AND discount_amount >= 0 AND subtotal >= 0),
    CONSTRAINT FK_order_item_seller_order FOREIGN KEY (seller_order_id) REFERENCES dbo.seller_order(seller_order_id)
);

CREATE INDEX IX_order_item_variant_id_ref ON dbo.order_item(variant_id_ref);

CREATE TABLE dbo.payment (
    payment_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_payment PRIMARY KEY,
    seller_order_id BIGINT NOT NULL,
    payment_type NVARCHAR(30) NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    due_at DATETIME2(0) NULL,
    provider NVARCHAR(100) NULL,
    transaction_ref NVARCHAR(255) NULL,
    status NVARCHAR(30) NOT NULL,
    paid_at DATETIME2(0) NULL,
    CONSTRAINT CK_payment_amount CHECK (amount > 0),
    CONSTRAINT FK_payment_seller_order FOREIGN KEY (seller_order_id) REFERENCES dbo.seller_order(seller_order_id)
);

CREATE INDEX IX_payment_seller_order_id ON dbo.payment(seller_order_id);
CREATE UNIQUE INDEX UX_payment_transaction_ref ON dbo.payment(transaction_ref) WHERE transaction_ref IS NOT NULL;

CREATE TABLE dbo.cancellation (
    cancellation_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_cancellation PRIMARY KEY,
    seller_order_id BIGINT NOT NULL,
    actor_type NVARCHAR(30) NOT NULL,
    actor_id_ref BIGINT NOT NULL,
    reason NVARCHAR(500) NOT NULL,
    refund_percent DECIMAL(5,2) NULL,
    status NVARCHAR(30) NOT NULL,
    cancelled_at DATETIME2(0) NULL,
    CONSTRAINT UQ_cancellation_seller_order UNIQUE (seller_order_id),
    CONSTRAINT CK_cancellation_refund_percent CHECK (refund_percent IS NULL OR (refund_percent >= 0 AND refund_percent <= 100)),
    CONSTRAINT FK_cancellation_seller_order FOREIGN KEY (seller_order_id) REFERENCES dbo.seller_order(seller_order_id)
);

CREATE INDEX IX_cancellation_actor_id_ref ON dbo.cancellation(actor_id_ref);

CREATE TABLE dbo.refund (
    refund_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_refund PRIMARY KEY,
    payment_id BIGINT NOT NULL,
    cancellation_id BIGINT NULL,
    amount DECIMAL(18,2) NOT NULL,
    reason NVARCHAR(500) NOT NULL,
    status NVARCHAR(30) NOT NULL,
    requested_at DATETIME2(0) NOT NULL CONSTRAINT DF_refund_requested_at DEFAULT SYSUTCDATETIME(),
    refunded_at DATETIME2(0) NULL,
    CONSTRAINT CK_refund_amount CHECK (amount > 0),
    CONSTRAINT FK_refund_payment FOREIGN KEY (payment_id) REFERENCES dbo.payment(payment_id),
    CONSTRAINT FK_refund_cancellation FOREIGN KEY (cancellation_id) REFERENCES dbo.cancellation(cancellation_id)
);

CREATE INDEX IX_refund_payment_id ON dbo.refund(payment_id);

CREATE TABLE dbo.dispute (
    dispute_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_dispute PRIMARY KEY,
    seller_order_id BIGINT NOT NULL,
    opened_by_user_id_ref BIGINT NOT NULL,
    reason NVARCHAR(500) NOT NULL,
    evidence_url NVARCHAR(1000) NULL,
    status NVARCHAR(30) NOT NULL,
    resolution NVARCHAR(MAX) NULL,
    opened_at DATETIME2(0) NOT NULL CONSTRAINT DF_dispute_opened_at DEFAULT SYSUTCDATETIME(),
    resolved_at DATETIME2(0) NULL,
    CONSTRAINT FK_dispute_seller_order FOREIGN KEY (seller_order_id) REFERENCES dbo.seller_order(seller_order_id)
);

CREATE INDEX IX_dispute_seller_order_id ON dbo.dispute(seller_order_id);
CREATE INDEX IX_dispute_opened_by_user_ref ON dbo.dispute(opened_by_user_id_ref);

CREATE TABLE dbo.settlement (
    settlement_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_settlement PRIMARY KEY,
    seller_order_id BIGINT NOT NULL,
    gross_amount DECIMAL(18,2) NOT NULL,
    fee_rate DECIMAL(5,2) NOT NULL CONSTRAINT DF_settlement_fee_rate DEFAULT 5.00,
    fee_amount DECIMAL(18,2) NOT NULL,
    supplier_payout_amount DECIMAL(18,2) NOT NULL,
    status NVARCHAR(30) NOT NULL,
    settled_at DATETIME2(0) NULL,
    CONSTRAINT UQ_settlement_seller_order UNIQUE (seller_order_id),
    CONSTRAINT CK_settlement_amounts CHECK (
        gross_amount >= 0 AND fee_rate >= 0 AND fee_rate <= 100
        AND fee_amount >= 0 AND supplier_payout_amount >= 0
    ),
    CONSTRAINT FK_settlement_seller_order FOREIGN KEY (seller_order_id) REFERENCES dbo.seller_order(seller_order_id)
);

CREATE TABLE dbo.shipment (
    shipment_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_shipment PRIMARY KEY,
    seller_order_id BIGINT NOT NULL,
    shipping_method NVARCHAR(100) NOT NULL,
    provider NVARCHAR(100) NULL,
    tracking_code NVARCHAR(255) NULL,
    expected_ship_at DATETIME2(0) NULL,
    shipped_at DATETIME2(0) NULL,
    expected_delivery_at DATETIME2(0) NULL,
    delivered_at DATETIME2(0) NULL,
    shipping_fee DECIMAL(18,2) NOT NULL CONSTRAINT DF_shipment_fee DEFAULT 0,
    status NVARCHAR(30) NOT NULL,
    CONSTRAINT CK_shipment_fee CHECK (shipping_fee >= 0),
    CONSTRAINT FK_shipment_seller_order FOREIGN KEY (seller_order_id) REFERENCES dbo.seller_order(seller_order_id)
);

CREATE INDEX IX_shipment_seller_order_id ON dbo.shipment(seller_order_id);
CREATE INDEX IX_shipment_tracking_code ON dbo.shipment(tracking_code) WHERE tracking_code IS NOT NULL;

CREATE TABLE dbo.review (
    review_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_review PRIMARY KEY,
    order_item_id BIGINT NOT NULL,
    customer_id_ref BIGINT NOT NULL,
    rating INT NOT NULL,
    comment NVARCHAR(MAX) NULL,
    created_at DATETIME2(0) NOT NULL CONSTRAINT DF_review_created_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT UQ_review_order_item UNIQUE (order_item_id),
    CONSTRAINT CK_review_rating CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT FK_review_order_item FOREIGN KEY (order_item_id) REFERENCES dbo.order_item(order_item_id)
);

CREATE INDEX IX_review_customer_id_ref ON dbo.review(customer_id_ref);
GO
