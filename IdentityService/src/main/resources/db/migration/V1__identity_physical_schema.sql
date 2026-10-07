-- MSS Identity Service - SQL Server physical schema

CREATE TABLE dbo.app_user (
    user_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_app_user PRIMARY KEY,
    email NVARCHAR(255) NOT NULL,
    status NVARCHAR(30) NOT NULL,
    created_at DATETIME2(0) NOT NULL CONSTRAINT DF_app_user_created_at DEFAULT SYSUTCDATETIME(),
    updated_at DATETIME2(0) NOT NULL CONSTRAINT DF_app_user_updated_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT UQ_app_user_email UNIQUE (email)
);

CREATE TABLE dbo.customer_profile (
    customer_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_customer_profile PRIMARY KEY,
    user_id BIGINT NOT NULL,
    full_name NVARCHAR(255) NOT NULL,
    phone NVARCHAR(30) NULL,
    created_at DATETIME2(0) NOT NULL CONSTRAINT DF_customer_profile_created_at DEFAULT SYSUTCDATETIME(),
    updated_at DATETIME2(0) NOT NULL CONSTRAINT DF_customer_profile_updated_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT UQ_customer_profile_user UNIQUE (user_id),
    CONSTRAINT FK_customer_profile_user FOREIGN KEY (user_id) REFERENCES dbo.app_user(user_id)
);

CREATE TABLE dbo.supplier_profile (
    supplier_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_supplier_profile PRIMARY KEY,
    user_id BIGINT NOT NULL,
    company_name NVARCHAR(255) NOT NULL,
    tax_code NVARCHAR(100) NOT NULL,
    verification_status NVARCHAR(30) NOT NULL,
    created_at DATETIME2(0) NOT NULL CONSTRAINT DF_supplier_profile_created_at DEFAULT SYSUTCDATETIME(),
    updated_at DATETIME2(0) NOT NULL CONSTRAINT DF_supplier_profile_updated_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT UQ_supplier_profile_user UNIQUE (user_id),
    CONSTRAINT UQ_supplier_profile_tax_code UNIQUE (tax_code),
    CONSTRAINT FK_supplier_profile_user FOREIGN KEY (user_id) REFERENCES dbo.app_user(user_id)
);

CREATE TABLE dbo.address (
    address_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_address PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    recipient_name NVARCHAR(255) NOT NULL,
    recipient_phone NVARCHAR(30) NOT NULL,
    address_line NVARCHAR(500) NOT NULL,
    ward NVARCHAR(100) NULL,
    district NVARCHAR(100) NULL,
    city NVARCHAR(100) NOT NULL,
    is_default BIT NOT NULL CONSTRAINT DF_address_is_default DEFAULT 0,
    CONSTRAINT FK_address_customer FOREIGN KEY (customer_id) REFERENCES dbo.customer_profile(customer_id)
);

CREATE INDEX IX_address_customer_id ON dbo.address(customer_id);
GO
