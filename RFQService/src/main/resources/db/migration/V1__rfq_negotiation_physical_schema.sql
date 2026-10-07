-- MSS RFQ & Negotiation Service - SQL Server physical schema
-- *_id_ref columns that point to Identity/Catalog/Commerce are intentionally NOT foreign keys.

CREATE TABLE dbo.rfq (
    rfq_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_rfq PRIMARY KEY,
    rfq_code NVARCHAR(100) NOT NULL,
    customer_id_ref BIGINT NOT NULL,
    title NVARCHAR(255) NOT NULL,
    description NVARCHAR(MAX) NULL,
    quantity INT NOT NULL,
    budget_amount DECIMAL(18,2) NULL,
    requested_delivery_date DATE NULL,
    delivery_location NVARCHAR(500) NULL,
    customization_requirements NVARCHAR(MAX) NULL,
    status NVARCHAR(30) NOT NULL,
    expires_at DATETIME2(0) NULL,
    created_at DATETIME2(0) NOT NULL CONSTRAINT DF_rfq_created_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT UQ_rfq_code UNIQUE (rfq_code),
    CONSTRAINT CK_rfq_quantity CHECK (quantity > 0),
    CONSTRAINT CK_rfq_budget CHECK (budget_amount IS NULL OR budget_amount >= 0)
);

CREATE INDEX IX_rfq_customer_id_ref ON dbo.rfq(customer_id_ref);

CREATE TABLE dbo.quotation (
    quotation_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_quotation PRIMARY KEY,
    rfq_id BIGINT NOT NULL,
    supplier_id_ref BIGINT NOT NULL,
    store_id_ref BIGINT NOT NULL,
    previous_quotation_id BIGINT NULL,
    version_no INT NOT NULL,
    total_amount DECIMAL(18,2) NOT NULL,
    deposit_percent DECIMAL(5,2) NOT NULL,
    valid_until DATETIME2(0) NOT NULL,
    lead_time_days INT NULL,
    proposed_delivery_date DATE NULL,
    shipping_method NVARCHAR(100) NULL,
    shipping_fee DECIMAL(18,2) NOT NULL CONSTRAINT DF_quotation_shipping_fee DEFAULT 0,
    payment_terms NVARCHAR(MAX) NULL,
    status NVARCHAR(30) NOT NULL,
    submitted_at DATETIME2(0) NULL,
    accepted_at DATETIME2(0) NULL,
    CONSTRAINT UQ_quotation_version UNIQUE (rfq_id, store_id_ref, version_no),
    CONSTRAINT CK_quotation_version CHECK (version_no > 0),
    CONSTRAINT CK_quotation_amount CHECK (total_amount >= 0),
    CONSTRAINT CK_quotation_deposit_percent CHECK (deposit_percent >= 0 AND deposit_percent <= 100),
    CONSTRAINT CK_quotation_lead_time CHECK (lead_time_days IS NULL OR lead_time_days >= 0),
    CONSTRAINT CK_quotation_shipping_fee CHECK (shipping_fee >= 0),
    CONSTRAINT CK_quotation_status CHECK (status IN ('DRAFT','SUBMITTED','REVISED','ACCEPTED','REJECTED','EXPIRED','CANCELLED')),
    CONSTRAINT FK_quotation_rfq FOREIGN KEY (rfq_id) REFERENCES dbo.rfq(rfq_id),
    CONSTRAINT FK_quotation_previous FOREIGN KEY (previous_quotation_id) REFERENCES dbo.quotation(quotation_id)
);

CREATE INDEX IX_quotation_supplier_id_ref ON dbo.quotation(supplier_id_ref);
CREATE INDEX IX_quotation_store_id_ref ON dbo.quotation(store_id_ref);

CREATE TABLE dbo.quotation_item (
    quotation_item_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_quotation_item PRIMARY KEY,
    quotation_id BIGINT NOT NULL,
    variant_id_ref BIGINT NULL,
    item_name_snapshot NVARCHAR(255) NOT NULL,
    sku_snapshot NVARCHAR(100) NULL,
    quantity INT NOT NULL,
    unit_price DECIMAL(18,2) NOT NULL,
    discount_amount DECIMAL(18,2) NOT NULL CONSTRAINT DF_quotation_item_discount DEFAULT 0,
    subtotal DECIMAL(18,2) NOT NULL,
    customization_note NVARCHAR(MAX) NULL,
    CONSTRAINT CK_quotation_item_quantity CHECK (quantity > 0),
    CONSTRAINT CK_quotation_item_amounts CHECK (unit_price >= 0 AND discount_amount >= 0 AND subtotal >= 0),
    CONSTRAINT FK_quotation_item_quotation FOREIGN KEY (quotation_id) REFERENCES dbo.quotation(quotation_id)
);

CREATE INDEX IX_quotation_item_variant_id_ref ON dbo.quotation_item(variant_id_ref);

CREATE TABLE dbo.conversation (
    conversation_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_conversation PRIMARY KEY,
    rfq_id BIGINT NOT NULL,
    customer_id_ref BIGINT NOT NULL,
    supplier_id_ref BIGINT NOT NULL,
    status NVARCHAR(30) NOT NULL,
    created_at DATETIME2(0) NOT NULL CONSTRAINT DF_conversation_created_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT UQ_conversation_rfq_supplier UNIQUE (rfq_id, supplier_id_ref),
    CONSTRAINT FK_conversation_rfq FOREIGN KEY (rfq_id) REFERENCES dbo.rfq(rfq_id)
);

CREATE INDEX IX_conversation_customer_id_ref ON dbo.conversation(customer_id_ref);
CREATE INDEX IX_conversation_supplier_id_ref ON dbo.conversation(supplier_id_ref);

CREATE TABLE dbo.conversation_message (
    message_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_conversation_message PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    sender_user_id_ref BIGINT NOT NULL,
    message_type NVARCHAR(30) NOT NULL,
    content NVARCHAR(MAX) NOT NULL,
    sent_at DATETIME2(0) NOT NULL CONSTRAINT DF_conversation_message_sent_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT FK_conversation_message_conversation FOREIGN KEY (conversation_id) REFERENCES dbo.conversation(conversation_id)
);

CREATE INDEX IX_conversation_message_conversation_sent ON dbo.conversation_message(conversation_id, sent_at);
CREATE INDEX IX_conversation_message_sender_user_ref ON dbo.conversation_message(sender_user_id_ref);
GO
