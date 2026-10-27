-- An RFQ is either public (ALL) or addressed to selected stores; existing RFQs stay public.
ALTER TABLE dbo.rfq ADD audience NVARCHAR(20) NOT NULL
    CONSTRAINT DF_rfq_audience DEFAULT 'ALL'
    CONSTRAINT CK_rfq_audience CHECK (audience IN ('ALL','SELECTED_STORES'));

-- Invited stores, with the owning supplier snapshotted so visibility checks need no Catalog call.
CREATE TABLE dbo.rfq_target_store (
    rfq_target_store_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_rfq_target_store PRIMARY KEY,
    rfq_id BIGINT NOT NULL,
    store_id_ref BIGINT NOT NULL,
    supplier_id_ref BIGINT NOT NULL,
    store_name_snapshot NVARCHAR(255) NOT NULL,
    CONSTRAINT UQ_rfq_target_store UNIQUE (rfq_id, store_id_ref),
    CONSTRAINT FK_rfq_target_store_rfq FOREIGN KEY (rfq_id) REFERENCES dbo.rfq(rfq_id)
);

CREATE INDEX IX_rfq_target_store_supplier ON dbo.rfq_target_store(supplier_id_ref, rfq_id);
