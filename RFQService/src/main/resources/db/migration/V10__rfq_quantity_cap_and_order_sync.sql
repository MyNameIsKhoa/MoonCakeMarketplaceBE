-- Accepted quotations may not exceed the RFQ quantity. An RFQ the system closed because it was full
-- reopens when one of the orders created from it is cancelled; a buyer-closed RFQ stays closed.
ALTER TABLE dbo.rfq ADD auto_closed BIT NOT NULL CONSTRAINT DF_rfq_auto_closed DEFAULT 0;

-- Last order status read from Commerce for an accepted quotation, and when it was read.
ALTER TABLE dbo.quotation_conversion ADD order_status NVARCHAR(30) NULL, order_checked_at DATETIME2(0) NULL;

-- ORDER_CANCELLED: accepted, but the order created from it was cancelled; its quantity no longer counts.
ALTER TABLE dbo.quotation DROP CONSTRAINT CK_quotation_status;
ALTER TABLE dbo.quotation ADD CONSTRAINT CK_quotation_status
    CHECK (status IN ('DRAFT','SUBMITTED','REVISED','ACCEPTED','REJECTED','EXPIRED','CANCELLED','ORDER_CANCELLED'));
