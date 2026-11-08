-- Per-line restore of confirmed stock (a partially cancelled checkout). Each row is the units of one variant that a
-- caller's restore key returned to stock; replays of the same key restore nothing twice, and cancel-confirmed of the
-- whole reservation only returns what was not restored here.
CREATE TABLE dbo.stock_reservation_restore (
    restore_line_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_stock_reservation_restore PRIMARY KEY,
    restore_key NVARCHAR(150) NOT NULL,
    reservation_key NVARCHAR(100) NOT NULL,
    variant_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    restored_at DATETIME2(0) NOT NULL CONSTRAINT DF_stock_reservation_restore_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT FK_stock_reservation_restore_reservation FOREIGN KEY (reservation_key)
        REFERENCES dbo.stock_reservation(reservation_key),
    CONSTRAINT FK_stock_reservation_restore_variant FOREIGN KEY (variant_id) REFERENCES dbo.product_variant(variant_id),
    CONSTRAINT UQ_stock_reservation_restore_line UNIQUE (restore_key, variant_id),
    CONSTRAINT CK_stock_reservation_restore_quantity CHECK (quantity > 0)
);

CREATE INDEX IX_stock_reservation_restore_reservation ON dbo.stock_reservation_restore(reservation_key, variant_id);
