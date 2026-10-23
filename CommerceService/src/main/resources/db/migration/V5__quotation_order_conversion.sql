CREATE TABLE dbo.quotation_order_conversion (
    conversion_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_quotation_order_conversion PRIMARY KEY,
    quotation_id_ref BIGINT NOT NULL,
    quotation_version INT NOT NULL,
    idempotency_key NVARCHAR(100) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    order_id BIGINT NULL,
    seller_order_id BIGINT NULL,
    CONSTRAINT UQ_quotation_order_conversion_quotation UNIQUE (quotation_id_ref),
    CONSTRAINT UQ_quotation_order_conversion_key UNIQUE (idempotency_key),
    CONSTRAINT CK_quotation_order_conversion_version CHECK (quotation_version > 0),
    CONSTRAINT FK_quotation_order_conversion_order FOREIGN KEY (order_id) REFERENCES dbo.customer_order(order_id),
    CONSTRAINT FK_quotation_order_conversion_seller FOREIGN KEY (seller_order_id) REFERENCES dbo.seller_order(seller_order_id)
);
CREATE UNIQUE INDEX UX_seller_order_quotation ON dbo.seller_order(quotation_id_ref) WHERE quotation_id_ref IS NOT NULL;
