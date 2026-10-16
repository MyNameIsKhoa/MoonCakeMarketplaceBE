param(
    [string]$BaseUrl = 'http://localhost:8080'
)

$ErrorActionPreference = 'Stop'
$base = $BaseUrl.TrimEnd('/')

function Get-ProfileStatus([hashtable]$headers) {
    try {
        $response = Invoke-WebRequest -Uri "$base/api/users/me" -Method Get -Headers $headers
        return [int]$response.StatusCode
    } catch {
        if ($null -ne $_.Exception.Response -and $null -ne $_.Exception.Response.StatusCode) {
            return [int]$_.Exception.Response.StatusCode
        }
        throw
    }
}

$ready = $false
for ($attempt = 0; $attempt -lt 30; $attempt++) {
    try {
        Invoke-WebRequest -Uri "$base/api/auth/login" -Method Post -ContentType 'application/json' -Body '{"email":"invalid","password":""}' | Out-Null
    } catch {
        if ($null -ne $_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 400) {
            $ready = $true
            break
        }
    }
    Start-Sleep -Seconds 2
}
if (-not $ready) {
    throw 'Gateway/Identity did not become ready within 60 seconds. Check docker compose logs.'
}

$email = "auth-demo-$([guid]::NewGuid().ToString('N').Substring(0, 12))@example.com"
$password = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
$registerBody = @{ fullName = 'Auth Demo'; email = $email; password = $password } | ConvertTo-Json -Compress
$registered = Invoke-RestMethod -Uri "$base/api/auth/register" -Method Post -ContentType 'application/json' -Body $registerBody
if ($registered.email -ne $email) { throw 'Registration did not return the expected user.' }

$loginBody = @{ email = $email; password = $password } | ConvertTo-Json -Compress
$login = Invoke-RestMethod -Uri "$base/api/auth/login" -Method Post -ContentType 'application/json' -Body $loginBody
if ([string]::IsNullOrWhiteSpace($login.accessToken)) { throw 'Login did not return an access token.' }

$missingStatus = Get-ProfileStatus @{}
$invalidStatus = Get-ProfileStatus @{ Authorization = 'Bearer definitely.invalid.token' }
$profile = Invoke-RestMethod -Uri "$base/api/users/me" -Method Get -Headers @{ Authorization = "Bearer $($login.accessToken)" }

if ($missingStatus -notin 401, 403) { throw "Missing token was not rejected: HTTP $missingStatus" }
if ($invalidStatus -notin 401, 403) { throw "Invalid token was not rejected: HTTP $invalidStatus" }
if ($profile.email -ne $email) { throw 'Valid token did not return the registered user profile.' }

Write-Host "PASS: register/login via Gateway, valid JWT returns profile for $email."
Write-Host "PASS: missing JWT rejected ($missingStatus); invalid JWT rejected ($invalidStatus)."
