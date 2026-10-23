CREATE TABLE dbo.quotation_conversion (
    conversion_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_quotation_conversion PRIMARY KEY,
    quotation_id BIGINT NOT NULL,
    customer_id_ref BIGINT NOT NULL,
    idempotency_key NVARCHAR(100) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    snapshot_json NVARCHAR(MAX) NOT NULL,
    status NVARCHAR(30) NOT NULL,
    order_id_ref BIGINT NULL,
    seller_order_id_ref BIGINT NULL,
    accepted_at DATETIME2(0) NOT NULL,
    updated_at DATETIME2(0) NOT NULL,
    CONSTRAINT UQ_quotation_conversion_quotation UNIQUE (quotation_id),
    CONSTRAINT UQ_quotation_conversion_key UNIQUE (customer_id_ref, idempotency_key),
    CONSTRAINT CK_quotation_conversion_status CHECK (status IN ('PENDING','COMPLETED')),
    CONSTRAINT FK_quotation_conversion_quotation FOREIGN KEY (quotation_id) REFERENCES dbo.quotation(quotation_id)
);
CREATE INDEX IX_quotation_conversion_recovery ON dbo.quotation_conversion(status, updated_at);
