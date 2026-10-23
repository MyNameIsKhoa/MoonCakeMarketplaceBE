-- Commerce can definitively refuse a persisted intent; FAILED stops recovery retries for it.
ALTER TABLE dbo.quotation_conversion DROP CONSTRAINT CK_quotation_conversion_status;
ALTER TABLE dbo.quotation_conversion ADD CONSTRAINT CK_quotation_conversion_status
    CHECK (status IN ('PENDING','COMPLETED','FAILED'));
