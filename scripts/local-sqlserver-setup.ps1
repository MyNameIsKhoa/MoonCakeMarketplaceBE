# Tạo database + login riêng cho MoonCake trên SQL Server cài sẵn trên máy (không dùng Docker).
# Yêu cầu: SQL Server bật mixed mode, tài khoản Windows hiện tại có quyền sysadmin, sqlcmd có trong PATH,
# file .env ở root backend chứa MOONCAKE_DB_USER và MOONCAKE_DB_PASSWORD.
# Chạy lại nhiều lần an toàn: chỉ tạo những gì chưa có, không xóa dữ liệu.
param(
    [string]$Server = 'localhost',
    [string[]]$Databases = @('mss_identity', 'mss_catalog_store')
)

$ErrorActionPreference = 'Stop'
$envPath = Join-Path $PSScriptRoot '..\.env'
if (-not (Test-Path $envPath)) { throw ".env not found at $envPath" }

$envValues = @{}
Get-Content $envPath | Where-Object { $_ -match '^[A-Z_]+=' } | ForEach-Object {
    $key, $value = $_ -split '=', 2
    $envValues[$key] = $value
}
$user = $envValues['MOONCAKE_DB_USER']
$password = $envValues['MOONCAKE_DB_PASSWORD']
if (-not $user -or -not $password) { throw 'MOONCAKE_DB_USER / MOONCAKE_DB_PASSWORD missing in .env' }
if ($password.Contains("'")) { throw 'MOONCAKE_DB_PASSWORD must not contain single quotes' }

function Invoke-Sql([string]$query) {
    & sqlcmd -S $Server -E -C -b -Q $query
    if ($LASTEXITCODE -ne 0) { throw "sqlcmd failed (exit $LASTEXITCODE)" }
}

Invoke-Sql "IF NOT EXISTS (SELECT 1 FROM sys.server_principals WHERE name = N'$user')
    CREATE LOGIN [$user] WITH PASSWORD = N'$password', CHECK_POLICY = OFF, DEFAULT_DATABASE = [master];"

foreach ($db in $Databases) {
    Invoke-Sql "IF DB_ID(N'$db') IS NULL CREATE DATABASE [$db];"
    # db_owner chỉ trên DB của dự án để Flyway tạo bảng/constraint; không cấp quyền server.
    Invoke-Sql "USE [$db];
        IF NOT EXISTS (SELECT 1 FROM sys.database_principals WHERE name = N'$user')
            CREATE USER [$user] FOR LOGIN [$user];
        ALTER ROLE db_owner ADD MEMBER [$user];"
    Write-Host "OK: database $db, user $user (db_owner)"
}
