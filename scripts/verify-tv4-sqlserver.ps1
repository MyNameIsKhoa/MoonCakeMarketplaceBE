param()

$ErrorActionPreference = 'Stop'
$taskRepository = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskEnvironmentFile = Join-Path $taskRepository '.env'
if (-not (Test-Path -LiteralPath $taskEnvironmentFile)) {
    throw 'Create .env from .env.example before running SQL Server verification.'
}
$taskPasswordLine = Get-Content -LiteralPath $taskEnvironmentFile |
    Where-Object { $_ -match '^MSSQL_SA_PASSWORD=' } | Select-Object -First 1
if (-not $taskPasswordLine) {
    throw 'MSSQL_SA_PASSWORD is missing from .env.'
}
$taskPassword = $taskPasswordLine.Substring($taskPasswordLine.IndexOf('=') + 1).Trim()
if (($taskPassword.StartsWith('"') -and $taskPassword.EndsWith('"')) -or
        ($taskPassword.StartsWith("'") -and $taskPassword.EndsWith("'"))) {
    $taskPassword = $taskPassword.Substring(1, $taskPassword.Length - 2)
}

$taskVariableNames = @('SPRING_DATASOURCE_URL', 'SPRING_DATASOURCE_USERNAME', 'SPRING_DATASOURCE_PASSWORD',
    'SPRING_DATASOURCE_DRIVER_CLASS_NAME', 'SPRING_FLYWAY_ENABLED', 'SPRING_JPA_HIBERNATE_DDL_AUTO')
$taskPreviousEnvironment = @{}
foreach ($taskName in $taskVariableNames) {
    $taskPreviousEnvironment[$taskName] = [Environment]::GetEnvironmentVariable($taskName, 'Process')
}

Push-Location -LiteralPath $taskRepository
try {
    [Environment]::SetEnvironmentVariable('SPRING_DATASOURCE_USERNAME', 'sa', 'Process')
    [Environment]::SetEnvironmentVariable('SPRING_DATASOURCE_PASSWORD', $taskPassword, 'Process')
    [Environment]::SetEnvironmentVariable('SPRING_DATASOURCE_DRIVER_CLASS_NAME', 'com.microsoft.sqlserver.jdbc.SQLServerDriver', 'Process')
    [Environment]::SetEnvironmentVariable('SPRING_FLYWAY_ENABLED', 'true', 'Process')
    [Environment]::SetEnvironmentVariable('SPRING_JPA_HIBERNATE_DDL_AUTO', 'none', 'Process')

    $taskModules = @(
        @{ Module = 'RFQService'; Container = 'rfq-db'; Port = 14334; Database = 'mss_rfq_tv4_test'; Test = 'QuotationAcceptanceIntegrationTest,RfqApplicationServiceTest,RfqRequestContractTest' },
        @{ Module = 'CommerceService'; Container = 'commerce-db'; Port = 14333; Database = 'mss_commerce_tv4_test'; Test = 'QuotationOrderIntegrationTest' }
    )
    foreach ($taskModule in $taskModules) {
        $taskDatabase = $taskModule.Database
        & docker compose exec -T $taskModule.Container /opt/mssql-tools18/bin/sqlcmd -C -S localhost -U sa -b `
            -Q "IF DB_ID(N'$taskDatabase') IS NULL EXEC(N'CREATE DATABASE [$taskDatabase]');"
        if ($LASTEXITCODE -ne 0) {
            throw "Cannot initialize isolated test database $taskDatabase. Start database containers first."
        }
        [Environment]::SetEnvironmentVariable('SPRING_DATASOURCE_URL',
            "jdbc:sqlserver://localhost:$($taskModule.Port);databaseName=$taskDatabase;encrypt=false;trustServerCertificate=true", 'Process')
        Write-Host "Verifying $($taskModule.Module) against $taskDatabase using SQL Server and Flyway."
        & (Join-Path $taskRepository 'RFQService/mvnw.cmd') -pl $taskModule.Module "-Dtest=$($taskModule.Test)" test
        if ($LASTEXITCODE -ne 0) {
            throw "$($taskModule.Module) SQL Server verification failed."
        }
    }
} finally {
    foreach ($taskName in $taskVariableNames) {
        [Environment]::SetEnvironmentVariable($taskName, $taskPreviousEnvironment[$taskName], 'Process')
    }
    $taskPassword = $null
    $taskPasswordLine = $null
    Pop-Location
}
