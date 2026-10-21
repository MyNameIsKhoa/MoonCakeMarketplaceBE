param([string]$Server = 'localhost')

$ErrorActionPreference = 'Stop'
$taskDatabase = 'mss_tv5_check_' + [Guid]::NewGuid().ToString('N').Substring(0, 12)
if ($taskDatabase -notmatch '^mss_tv5_check_[a-f0-9]{12}$') { throw 'Unexpected validation database name' }
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskSqlcmd = Get-Command sqlcmd -ErrorAction Stop

function Invoke-TaskSql([string[]]$SqlArguments) {
    & $taskSqlcmd.Source -S $Server -E -C -I -b @SqlArguments
    if ($LASTEXITCODE -ne 0) { throw "SQL Server validation failed ($LASTEXITCODE)" }
}

Invoke-TaskSql @('-Q', "CREATE DATABASE [$taskDatabase]")
try {
    foreach ($taskMigration in @('V1__commerce_physical_schema.sql', 'V2__tv5_finance.sql')) {
        Invoke-TaskSql @('-d', $taskDatabase, '-i', (Join-Path $taskRoot "CommerceService/src/main/resources/db/migration/$taskMigration"))
    }
    $taskChecks = @'
SET NOCOUNT ON;
SET XACT_ABORT OFF;
INSERT INTO customer_order(order_no,customer_id_ref,source_type,subtotal,grand_total,status)
VALUES('TV5-CHECK',10,'RETAIL',10001,10001,'ACCEPTED');
INSERT INTO seller_order(order_id,seller_order_no,store_id_ref,supplier_id_ref,
store_name_snapshot,supplier_name_snapshot,subtotal,total_amount,status)
VALUES(1,'TV5-SELLER-CHECK',1,20,'Store','Supplier',10001,10001,'ACCEPTED');
INSERT INTO finance_context(seller_order_id,customer_id,supplier_id,total_amount,accepted,source_version)
VALUES(1,10,20,10001,1,1);
-- Filtered indexes must permit multiple uninitiated obligations.
INSERT INTO payment(seller_order_id,payment_type,amount,status) VALUES(1,'DEPOSIT',3000,'PENDING'),(1,'REMAINING',7001,'PENDING');
BEGIN TRY
    INSERT INTO payment(seller_order_id,payment_type,amount,status) VALUES(1,'FULL',0.5,'PENDING');
    THROW 51000, 'Fractional VND was accepted', 1;
END TRY
BEGIN CATCH
    IF ERROR_NUMBER() <> 547 THROW;
END CATCH;
UPDATE payment SET idempotency_key='tv5-unique-key',order_code=NEXT VALUE FOR finance_order_code WHERE payment_id=1;
BEGIN TRY
    UPDATE payment SET idempotency_key='tv5-unique-key' WHERE payment_id=2;
    THROW 51000, 'Duplicate idempotency key was accepted', 1;
END TRY
BEGIN CATCH
    IF ERROR_NUMBER() NOT IN (2601,2627) THROW;
END CATCH;
UPDATE payment SET payment_link_id='tv5-link',transaction_ref='tv5-provider-ref' WHERE payment_id=1;
BEGIN TRY
    UPDATE payment SET transaction_ref='tv5-provider-ref' WHERE payment_id=2;
    THROW 51000, 'Duplicate provider reference was accepted', 1;
END TRY
BEGIN CATCH
    IF ERROR_NUMBER() NOT IN (2601,2627) THROW;
END CATCH;
PRINT 'TV5 SQL Server migrations and constraints passed';
'@
    Invoke-TaskSql @('-d', $taskDatabase, '-Q', $taskChecks)
} finally {
    # This exact disposable database was created above by this validation run.
    Invoke-TaskSql @('-d', 'master', '-Q', "DROP DATABASE [$taskDatabase]")
}
