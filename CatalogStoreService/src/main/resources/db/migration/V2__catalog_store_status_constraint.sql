ALTER TABLE dbo.store ADD CONSTRAINT CK_store_status
    CHECK (status IN ('DRAFT', 'ACTIVE', 'INACTIVE', 'SUSPENDED'));
