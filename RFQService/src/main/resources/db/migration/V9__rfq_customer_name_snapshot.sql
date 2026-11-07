-- Buyer's name at RFQ creation, shown to suppliers in conversations; existing RFQs keep NULL.
ALTER TABLE dbo.rfq ADD customer_name_snapshot NVARCHAR(255) NULL;
