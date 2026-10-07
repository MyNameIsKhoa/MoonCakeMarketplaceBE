-- MSS Catalog & Store Service - SQL Server physical schema
-- supplier_id_ref is a cross-service logical reference to Identity; no FK is created.

CREATE TABLE dbo.store (
    store_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_store PRIMARY KEY,
    supplier_id_ref BIGINT NOT NULL,
    store_code NVARCHAR(100) NOT NULL,
    name NVARCHAR(255) NOT NULL,
    slug NVARCHAR(255) NOT NULL,
    logo_url NVARCHAR(500) NULL,
    banner_url NVARCHAR(500) NULL,
    theme_key NVARCHAR(100) NULL,
    status NVARCHAR(30) NOT NULL,
    CONSTRAINT UQ_store_code UNIQUE (store_code),
    CONSTRAINT UQ_store_slug UNIQUE (slug)
);

CREATE INDEX IX_store_supplier_id_ref ON dbo.store(supplier_id_ref);

CREATE TABLE dbo.category (
    category_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_category PRIMARY KEY,
    parent_category_id BIGINT NULL,
    name NVARCHAR(255) NOT NULL,
    slug NVARCHAR(255) NOT NULL,
    status NVARCHAR(30) NOT NULL,
    CONSTRAINT UQ_category_slug UNIQUE (slug),
    CONSTRAINT FK_category_parent FOREIGN KEY (parent_category_id) REFERENCES dbo.category(category_id)
);

CREATE TABLE dbo.product (
    product_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_product PRIMARY KEY,
    store_id BIGINT NOT NULL,
    category_id BIGINT NOT NULL,
    product_code NVARCHAR(100) NOT NULL,
    name NVARCHAR(255) NOT NULL,
    description NVARCHAR(MAX) NULL,
    status NVARCHAR(30) NOT NULL,
    created_at DATETIME2(0) NOT NULL CONSTRAINT DF_product_created_at DEFAULT SYSUTCDATETIME(),
    updated_at DATETIME2(0) NOT NULL CONSTRAINT DF_product_updated_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT UQ_product_store_code UNIQUE (store_id, product_code),
    CONSTRAINT FK_product_store FOREIGN KEY (store_id) REFERENCES dbo.store(store_id),
    CONSTRAINT FK_product_category FOREIGN KEY (category_id) REFERENCES dbo.category(category_id)
);

CREATE INDEX IX_product_category_id ON dbo.product(category_id);

CREATE TABLE dbo.product_variant (
    variant_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_product_variant PRIMARY KEY,
    product_id BIGINT NOT NULL,
    sku NVARCHAR(100) NOT NULL,
    variant_name NVARCHAR(255) NOT NULL,
    image_url NVARCHAR(500) NULL,
    base_price DECIMAL(18,2) NOT NULL,
    stock_quantity INT NOT NULL CONSTRAINT DF_product_variant_stock DEFAULT 0,
    status NVARCHAR(30) NOT NULL,
    CONSTRAINT UQ_product_variant_sku UNIQUE (sku),
    CONSTRAINT CK_product_variant_price CHECK (base_price >= 0),
    CONSTRAINT CK_product_variant_stock CHECK (stock_quantity >= 0),
    CONSTRAINT FK_product_variant_product FOREIGN KEY (product_id) REFERENCES dbo.product(product_id)
);

CREATE TABLE dbo.promotion (
    promotion_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_promotion PRIMARY KEY,
    store_id BIGINT NOT NULL,
    name NVARCHAR(255) NOT NULL,
    start_at DATETIME2(0) NOT NULL,
    end_at DATETIME2(0) NOT NULL,
    status NVARCHAR(30) NOT NULL,
    CONSTRAINT CK_promotion_time CHECK (end_at > start_at),
    CONSTRAINT FK_promotion_store FOREIGN KEY (store_id) REFERENCES dbo.store(store_id)
);

CREATE TABLE dbo.promotion_rule (
    promotion_rule_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_promotion_rule PRIMARY KEY,
    promotion_id BIGINT NOT NULL,
    target_variant_id BIGINT NULL,
    reward_variant_id BIGINT NULL,
    rule_type NVARCHAR(30) NOT NULL,
    min_quantity INT NULL,
    discount_percent DECIMAL(5,2) NULL,
    discount_amount DECIMAL(18,2) NULL,
    reward_quantity INT NULL,
    CONSTRAINT CK_promotion_rule_type CHECK (rule_type IN ('PERCENT_DISCOUNT','FIXED_DISCOUNT','BUY_X_GET_Y')),
    CONSTRAINT CK_promotion_rule_min_quantity CHECK (min_quantity IS NULL OR min_quantity > 0),
    CONSTRAINT CK_promotion_rule_discount_percent CHECK (discount_percent IS NULL OR (discount_percent >= 0 AND discount_percent <= 100)),
    CONSTRAINT CK_promotion_rule_discount_amount CHECK (discount_amount IS NULL OR discount_amount >= 0),
    CONSTRAINT CK_promotion_rule_reward_quantity CHECK (reward_quantity IS NULL OR reward_quantity > 0),
    CONSTRAINT FK_promotion_rule_promotion FOREIGN KEY (promotion_id) REFERENCES dbo.promotion(promotion_id),
    CONSTRAINT FK_promotion_rule_target_variant FOREIGN KEY (target_variant_id) REFERENCES dbo.product_variant(variant_id),
    CONSTRAINT FK_promotion_rule_reward_variant FOREIGN KEY (reward_variant_id) REFERENCES dbo.product_variant(variant_id)
);

CREATE INDEX IX_promotion_rule_target_variant ON dbo.promotion_rule(target_variant_id);
CREATE INDEX IX_promotion_rule_reward_variant ON dbo.promotion_rule(reward_variant_id);
GO
