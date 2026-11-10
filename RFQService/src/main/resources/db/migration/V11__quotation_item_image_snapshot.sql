-- Images belong to the saved quotation version. Leave historical rows and conversion JSON unchanged.
ALTER TABLE dbo.quotation_item ADD image_url_snapshot NVARCHAR(500) NULL;
