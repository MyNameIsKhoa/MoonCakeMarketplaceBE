-- Keep the oldest marked default; otherwise promote the oldest address for each customer.
WITH ranked AS (
    SELECT address_id, customer_id, is_default,
           ROW_NUMBER() OVER (PARTITION BY customer_id ORDER BY CASE WHEN is_default = 1 THEN 0 ELSE 1 END, address_id) AS rn
    FROM dbo.address
)
UPDATE a SET is_default = CASE WHEN r.rn = 1 THEN 1 ELSE 0 END
FROM dbo.address a JOIN ranked r ON r.address_id = a.address_id;

CREATE UNIQUE INDEX UQ_address_one_default_per_customer
    ON dbo.address(customer_id) WHERE is_default = 1;

CREATE TABLE dbo.supplier_verification_audit (
    audit_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_supplier_verification_audit PRIMARY KEY,
    supplier_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    previous_status NVARCHAR(30) NULL,
    new_status NVARCHAR(30) NOT NULL,
    reason NVARCHAR(1000) NOT NULL,
    acted_at DATETIME2(0) NOT NULL CONSTRAINT DF_supplier_verification_audit_acted_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT FK_supplier_verification_audit_supplier FOREIGN KEY (supplier_id) REFERENCES dbo.supplier_profile(supplier_id),
    CONSTRAINT FK_supplier_verification_audit_actor FOREIGN KEY (actor_user_id) REFERENCES dbo.app_user(user_id)
);
CREATE INDEX IX_supplier_verification_audit_supplier_time
    ON dbo.supplier_verification_audit(supplier_id, acted_at DESC, audit_id DESC);
