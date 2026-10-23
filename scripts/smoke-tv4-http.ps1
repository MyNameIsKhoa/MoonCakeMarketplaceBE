#Requires -Version 7.0
<#
Run only with newly created fixture accounts: this creates RFQs, quotations and an unpaid order.
Prefer setting TV4_BUYER_TOKEN / TV4_SUPPLIER_TOKEN in the current process; tokens are never printed.
Example (placeholders only):
  $env:TV4_BUYER_TOKEN = '<fresh-fixture-buyer-token>'
  $env:TV4_SUPPLIER_TOKEN = '<fresh-fixture-supplier-token>'
  .\scripts\smoke-tv4-http.ps1 -StoreId '<owned-active-store-id>' -AddressId '<buyer-address-id>' -FixtureAccountsConfirmed
The returned IDs can be supplied to SmokeTv4Chat.java through TV4_CONVERSATION_ID.
#>
[CmdletBinding()]
param(
    [string] $BaseUrl = 'http://localhost:8080',
    [string] $BuyerToken = $env:TV4_BUYER_TOKEN,
    [string] $SupplierToken = $env:TV4_SUPPLIER_TOKEN,
    [string] $OtherSupplierToken = $env:TV4_OTHER_SUPPLIER_TOKEN,
    [string] $StoreId = $env:TV4_STORE_ID,
    [string] $AddressId = $env:TV4_ADDRESS_ID,
    [string] $ResultPath,
    [switch] $FixtureAccountsConfirmed
)

$ErrorActionPreference = 'Stop'
if (-not $FixtureAccountsConfirmed) { throw 'Confirm that all supplied accounts are newly created fixtures using -FixtureAccountsConfirmed.' }
if ([string]::IsNullOrWhiteSpace($BuyerToken) -or [string]::IsNullOrWhiteSpace($SupplierToken)) {
    throw 'Set buyer and supplier fixture bearer tokens through parameters or process environment.'
}
if ($StoreId -notmatch '^[1-9][0-9]*$' -or $AddressId -notmatch '^[1-9][0-9]*$') {
    throw 'StoreId and AddressId must identify the supplied fixture accounts resources.'
}
$BaseUrl = $BaseUrl.TrimEnd('/')
$baseUri = [Uri]$BaseUrl
if ($baseUri.Scheme -notin @('http', 'https') -or $baseUri.Query -or $baseUri.UserInfo) {
    throw 'BaseUrl must be an HTTP(S) origin without credentials or query parameters.'
}

function Invoke-Tv4Api {
    param([string]$Method, [string]$Path, [string]$Token, [object]$Body, [string]$Key, [int[]]$Expected = @(200))
    $headers = @{ Accept = 'application/json' }
    if ($Token) { $headers.Authorization = 'Bearer ' + $Token }
    if ($Key) { $headers['Idempotency-Key'] = $Key }
    $request = @{ Method = $Method; Uri = $BaseUrl + $Path; Headers = $headers; SkipHttpErrorCheck = $true; TimeoutSec = 30 }
    if ($null -ne $Body) {
        $request.ContentType = 'application/json; charset=utf-8'
        $request.Body = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 20 -Compress))
    }
    $response = Invoke-WebRequest @request
    $parsed = if ($response.Content) { $response.Content | ConvertFrom-Json -Depth 30 } else { $null }
    if ([int]$response.StatusCode -notin $Expected) {
        $code = if ($parsed.code) { $parsed.code } else { 'NO_PROBLEM_CODE' }
        throw "$Method $Path failed: HTTP $($response.StatusCode), code=$code. No credentials are included in this error."
    }
    return [pscustomobject]@{ Status = [int]$response.StatusCode; Body = $parsed }
}

function Assert-Smoke([bool]$Condition, [string]$Description) {
    if (-not $Condition) { throw "TV4 smoke assertion failed: $Description" }
}

$runId = [Guid]::NewGuid().ToString('N')
$profile = (Invoke-Tv4Api -Method GET -Path '/api/supplier-profile' -Token $SupplierToken).Body
$store = (Invoke-Tv4Api -Method GET -Path "/api/stores/$StoreId" -Token $SupplierToken).Body
Assert-Smoke ([string]$profile.supplierId -eq [string]$store.supplierIdRef) 'store belongs to the supplied supplier fixture'
Assert-Smoke ($store.status -eq 'ACTIVE') 'store is active'
$delivery = [DateTimeOffset]::UtcNow.AddDays(14).ToString('yyyy-MM-dd')
$expiry = [DateTimeOffset]::UtcNow.AddDays(2).ToString('yyyy-MM-ddTHH:mm:ssZ')

$rfq = (Invoke-Tv4Api -Method POST -Path '/api/rfqs' -Token $BuyerToken -Expected @(201) -Body @{
    title = "TV4 fixture RFQ $runId"; description = 'Runtime fixture for Gateway, Feign and SQL Server verification'
    quantity = 10; budgetAmount = '2000000.00'; requestedDeliveryDate = $delivery
    deliveryLocation = 'PRIVATE-FIXTURE-LOCATION'; customizationRequirements = 'PRIVATE-FIXTURE-REQUIREMENTS'; expiresAt = $expiry
}).Body
Assert-Smoke ($rfq.rfqId -is [string]) 'public IDs use decimal strings'
Assert-Smoke ($rfq.customerId -and $rfq.deliveryLocation -eq 'PRIVATE-FIXTURE-LOCATION') 'buyer sees private owner fields'
$rfqId = $rfq.rfqId
$public = (Invoke-Tv4Api -Method GET -Path "/api/rfqs/$rfqId").Body
Assert-Smoke (-not $public.customerId -and -not $public.deliveryLocation -and -not $public.customizationRequirements) 'public RFQ does not expose owner fields'
Assert-Smoke (-not $public.quotations -and -not $public.conversations) 'public RFQ does not expose quotations or conversations'
$publicList = (Invoke-Tv4Api -Method GET -Path "/api/rfqs?q=$runId").Body
Assert-Smoke (@($publicList.items).Count -eq 1) 'public search locates the published fixture RFQ'

$quotationBody = @{
    storeId = [string]$StoreId; depositPercent = '30.00'; validUntil = $expiry; leadTimeDays = 5
    proposedDeliveryDate = $delivery; shippingMethod = 'FIXTURE_DELIVERY'; shippingFee = '50000.00'
    paymentTerms = 'Fixture only: do not pay'; items = @(@{
        itemNameSnapshot = 'TV4 custom fixture mooncakes'; quantity = 10; unitPrice = '100000.00'
        discountAmount = '10000.00'; customizationNote = 'Fixture custom line'
    })
}
$q1 = (Invoke-Tv4Api -Method POST -Path "/api/rfqs/$rfqId/quotations" -Token $SupplierToken `
    -Key "quote-$runId" -Body $quotationBody -Expected @(201)).Body
Assert-Smoke ($q1.version -eq 1 -and $q1.totalAmount -eq '1040000.00') 'quotation version and server total are correct'
$quoteReplay = (Invoke-Tv4Api -Method POST -Path "/api/rfqs/$rfqId/quotations" -Token $SupplierToken `
    -Key "quote-$runId" -Body $quotationBody -Expected @(200, 201)).Body
Assert-Smoke ($quoteReplay.quotationId -eq $q1.quotationId) 'quotation command replay reuses the same version'
$revisionBody = $quotationBody.Clone()
$revisionBody.Remove('storeId')
$revisionBody.expectedVersion = 1
$revisionBody.items = @(@{ itemNameSnapshot = 'TV4 custom fixture mooncakes'; quantity = 10; unitPrice = '95000.00'; discountAmount = '10000.00' })
$q2 = (Invoke-Tv4Api -Method POST -Path "/api/quotations/$($q1.quotationId)/revisions" -Token $SupplierToken `
    -Key "revision-$runId" -Body $revisionBody -Expected @(201)).Body
Assert-Smoke ($q2.version -eq 2 -and $q2.previousQuotationId -eq $q1.quotationId -and $q2.totalAmount -eq '990000.00') 'revision creates a new immutable quotation version'
$old = (Invoke-Tv4Api -Method GET -Path "/api/quotations/$($q1.quotationId)" -Token $BuyerToken).Body
Assert-Smoke ($old.status -eq 'REVISED' -and $old.totalAmount -eq '1040000.00') 'old revision retains its own price'
$comparison = (Invoke-Tv4Api -Method GET -Path "/api/rfqs/$rfqId/quotations" -Token $BuyerToken).Body
Assert-Smoke (@($comparison.items).Count -eq 2) 'buyer compares both version summaries'
$conversation = (Invoke-Tv4Api -Method POST -Path "/api/rfqs/$rfqId/conversations" -Token $BuyerToken `
    -Body @{ supplierId = [string]$profile.supplierId } -Expected @(201)).Body
$conversationReplay = (Invoke-Tv4Api -Method POST -Path "/api/rfqs/$rfqId/conversations" -Token $SupplierToken `
    -Body @{} -Expected @(200)).Body
Assert-Smoke ($conversationReplay.conversationId -eq $conversation.conversationId) 'supplier reuses the buyer conversation'

if ($OtherSupplierToken) {
    $null = Invoke-Tv4Api -Method GET -Path "/api/quotations/$($q2.quotationId)" -Token $OtherSupplierToken -Expected @(403)
    $outsider = Invoke-Tv4Api -Method GET -Path "/api/rfqs/$rfqId/quotations" -Token $OtherSupplierToken -Expected @(200, 403)
    if ($outsider.Status -eq 200) {
        Assert-Smoke (@($outsider.Body.items).Count -eq 0) 'supplier cannot list competitor quotations'
    }
    $null = Invoke-Tv4Api -Method GET -Path "/api/conversations/$($conversation.conversationId)" -Token $OtherSupplierToken -Expected @(403)
}

$acceptBody = @{ quotationVersion = 2; addressId = [string]$AddressId }
$accepted = (Invoke-Tv4Api -Method POST -Path "/api/quotations/$($q2.quotationId)/accept" -Token $BuyerToken `
    -Key "accept-$runId" -Body $acceptBody -Expected @(200, 202)).Body
for ($attempt = 0; $accepted.conversionStatus -eq 'PENDING' -and $attempt -lt 20; $attempt++) {
    Start-Sleep -Milliseconds 1000
    $accepted = (Invoke-Tv4Api -Method GET -Path "/api/quotations/$($q2.quotationId)/order" -Token $BuyerToken).Body
}
Assert-Smoke ($accepted.conversionStatus -eq 'COMPLETED' -and $accepted.orderId -and $accepted.sellerOrderId) 'acceptance returns a committed Commerce order'
$acceptReplay = (Invoke-Tv4Api -Method POST -Path "/api/quotations/$($q2.quotationId)/accept" -Token $BuyerToken `
    -Key "accept-$runId" -Body $acceptBody -Expected @(200)).Body
Assert-Smoke ($acceptReplay.orderId -eq $accepted.orderId -and $acceptReplay.sellerOrderId -eq $accepted.sellerOrderId) 'same acceptance key reuses order and seller order'
$acceptedQuote = (Invoke-Tv4Api -Method GET -Path "/api/quotations/$($q2.quotationId)" -Token $BuyerToken).Body
Assert-Smoke ($acceptedQuote.status -eq 'ACCEPTED') 'quotation becomes accepted after confirmed Commerce commit'

$result = [pscustomobject]@{
    result = 'PASS'; rfqId = $rfqId; quotationId = $q2.quotationId; conversationId = $conversation.conversationId
    orderId = $accepted.orderId; sellerOrderId = $accepted.sellerOrderId
    outsiderChecks = [bool]$OtherSupplierToken
}
if ($ResultPath) { $result | ConvertTo-Json | Set-Content -LiteralPath $ResultPath -Encoding utf8 }
$result
