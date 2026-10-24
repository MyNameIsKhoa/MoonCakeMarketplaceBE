# One-time repair for local databases that applied finance as V2 before the merge renamed it to V3.
# Does not change migration files, delete business data, or disable Flyway validation.
param([string]$Server = 'localhost')
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskFinance = Join-Path $taskRoot 'CommerceService/src/main/resources/db/migration/V3__tv5_finance.sql'
$taskCart = Join-Path $taskRoot 'CommerceService/src/main/resources/db/migration/V2__commerce_active_cart_uniqueness.sql'

if (-not ('CommerceHistoryChecksum' -as [type])) {
    Add-Type @'
using System;
using System.IO;
using System.Text;
public static class CommerceHistoryChecksum {
    public static int Calculate(string path) {
        uint crc = 0xffffffff;
        foreach (string line in File.ReadLines(path, Encoding.UTF8)) {
            foreach (byte b in Encoding.UTF8.GetBytes(line.TrimStart('\ufeff'))) {
                crc ^= b;
                for (int i = 0; i < 8; i++) crc = (crc >> 1) ^ ((crc & 1) != 0 ? 0xedb88320u : 0u);
            }
        }
        return unchecked((int)(crc ^ 0xffffffff));
    }
}
'@
}
$taskFinanceChecksum = [CommerceHistoryChecksum]::Calculate($taskFinance)
$taskCartChecksum = [CommerceHistoryChecksum]::Calculate($taskCart)
# Hard guard: only reconcile the exact known, originally applied finance script.
if ($taskFinanceChecksum -ne 1611821405) { throw 'Finance migration differs from the known V2; manual schema review required.' }
if ($taskCartChecksum -ne 1763569280) { throw 'Cart migration differs from the reviewed V2; manual review required.' }
$taskCartSql = [IO.File]::ReadAllText($taskCart)
$taskSql = @"
USE [mss_commerce];
SET NOCOUNT ON;
SET XACT_ABORT ON;
BEGIN TRANSACTION;
IF EXISTS(SELECT 1 FROM dbo.flyway_schema_history WITH (UPDLOCK,HOLDLOCK)
          WHERE version='2' AND script='V2__tv5_finance.sql' AND checksum=1611821405 AND success=1)
BEGIN
    IF EXISTS(SELECT 1 FROM dbo.flyway_schema_history WHERE version='3')
        THROW 51000, 'V3 already exists; stop to review history.', 1;
    IF NOT EXISTS(SELECT 1 FROM sys.tables WHERE name='finance_context')
        THROW 51000, 'Expected finance schema missing; stop.', 1;
    IF EXISTS(SELECT 1 FROM dbo.cart WHERE status='ACTIVE' GROUP BY customer_id_ref HAVING COUNT(*)>1)
        THROW 51000, 'Duplicate active carts require manual resolution; no changes made.', 1;
    IF OBJECT_ID('dbo.flyway_history_before_tv5_rename') IS NOT NULL
        THROW 51000, 'History backup already exists; stop to review.', 1;
    SELECT * INTO dbo.flyway_history_before_tv5_rename FROM dbo.flyway_schema_history;
    UPDATE dbo.flyway_schema_history
       SET version='3',description='tv5 finance',script='V3__tv5_finance.sql'
     WHERE version='2' AND script='V2__tv5_finance.sql' AND checksum=1611821405 AND success=1;
    IF NOT EXISTS(SELECT 1 FROM sys.indexes WHERE object_id=OBJECT_ID('dbo.cart') AND name='UX_cart_active_customer')
    BEGIN
        $taskCartSql
    END
    INSERT INTO dbo.flyway_schema_history(installed_rank,version,description,type,script,checksum,installed_by,execution_time,success)
    SELECT MAX(installed_rank)+1,'2','commerce active cart uniqueness','SQL',
           'V2__commerce_active_cart_uniqueness.sql',$taskCartChecksum,CURRENT_USER,0,1
    FROM dbo.flyway_schema_history;
END
ELSE IF NOT EXISTS(SELECT 1 FROM dbo.flyway_schema_history
                   WHERE version='3' AND script='V3__tv5_finance.sql' AND checksum=1611821405 AND success=1)
    THROW 51000, 'Database history is not the expected legacy state; no changes made.', 1;
COMMIT;
SELECT version,script,checksum,success FROM dbo.flyway_schema_history ORDER BY installed_rank;
"@
& sqlcmd -S $Server -E -C -I -b -W -s '|' -Q $taskSql
if ($LASTEXITCODE -ne 0) { throw 'Local history reconciliation failed; inspect SQL Server error above.' }
