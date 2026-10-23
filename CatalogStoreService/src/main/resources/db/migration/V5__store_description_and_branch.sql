-- Mô tả gian hàng + danh sách chi nhánh (cửa hàng vật lý) của store.
ALTER TABLE dbo.store ADD description NVARCHAR(2000) NULL;

CREATE TABLE dbo.store_branch (
    store_branch_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_store_branch PRIMARY KEY,
    store_id BIGINT NOT NULL,
    name NVARCHAR(255) NOT NULL,
    address_line NVARCHAR(500) NOT NULL,
    ward NVARCHAR(100) NULL,
    district NVARCHAR(100) NULL,
    city NVARCHAR(100) NOT NULL,
    phone NVARCHAR(30) NULL,
    opening_hours NVARCHAR(255) NULL,
    is_primary BIT NOT NULL CONSTRAINT DF_store_branch_is_primary DEFAULT 0,
    status NVARCHAR(30) NOT NULL,
    CONSTRAINT FK_store_branch_store FOREIGN KEY (store_id) REFERENCES dbo.store(store_id),
    CONSTRAINT CK_store_branch_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE INDEX IX_store_branch_store_id ON dbo.store_branch(store_id);
-- Mỗi store tối đa một chi nhánh chính.
CREATE UNIQUE INDEX UQ_store_branch_primary ON dbo.store_branch(store_id) WHERE is_primary = 1;
