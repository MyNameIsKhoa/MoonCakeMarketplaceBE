ALTER TABLE dbo.dispute ADD seller_response NVARCHAR(1000) NULL,
    responded_at DATETIME2(0) NULL, escalated_at DATETIME2(0) NULL,
    decision_type VARCHAR(30) NULL, awarded_amount DECIMAL(18,2) NULL;
ALTER TABLE dbo.refund ADD dispute_id BIGINT NULL;
ALTER TABLE dbo.settlement ADD refunded_amount DECIMAL(18,2) NOT NULL
    CONSTRAINT DF_settlement_refunded_amount DEFAULT 0;
GO

ALTER TABLE dbo.dispute ADD CONSTRAINT CK_dispute_award CHECK (awarded_amount IS NULL OR awarded_amount > 0);
ALTER TABLE dbo.refund ADD CONSTRAINT FK_refund_dispute FOREIGN KEY (dispute_id) REFERENCES dbo.dispute(dispute_id);
CREATE INDEX IX_refund_dispute_id ON dbo.refund(dispute_id) WHERE dispute_id IS NOT NULL;
ALTER TABLE dbo.settlement ADD CONSTRAINT CK_settlement_gross_allocation
    CHECK (refunded_amount >= 0
        AND gross_amount = refunded_amount + fee_amount + supplier_payout_amount);
