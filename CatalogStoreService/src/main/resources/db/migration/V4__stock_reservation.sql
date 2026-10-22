-- Catalog owns stock holds. stock_quantity remains on-hand until confirmation.
CREATE TABLE dbo.stock_reservation (
    reservation_key NVARCHAR(100) NOT NULL CONSTRAINT PK_stock_reservation PRIMARY KEY,
    status NVARCHAR(20) NOT NULL,
    expires_at DATETIME2(0) NOT NULL,
    CONSTRAINT CK_stock_reservation_status CHECK (status IN ('HELD', 'CONFIRMED', 'RELEASED'))
);

CREATE TABLE dbo.stock_reservation_line (
    reservation_line_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_stock_reservation_line PRIMARY KEY,
    reservation_key NVARCHAR(100) NOT NULL,
    variant_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    CONSTRAINT FK_stock_reservation_line_header FOREIGN KEY (reservation_key) REFERENCES dbo.stock_reservation(reservation_key),
    CONSTRAINT FK_stock_reservation_line_variant FOREIGN KEY (variant_id) REFERENCES dbo.product_variant(variant_id),
    CONSTRAINT UQ_stock_reservation_line_variant UNIQUE (reservation_key, variant_id),
    CONSTRAINT CK_stock_reservation_line_quantity CHECK (quantity > 0)
);

CREATE INDEX IX_stock_reservation_line_variant ON dbo.stock_reservation_line(variant_id);
CREATE INDEX IX_stock_reservation_status_expiry ON dbo.stock_reservation(status, expires_at);
