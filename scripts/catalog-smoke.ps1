# Smoke test end-to-end cho phần Catalog của TV2 trên SQL Server thật, đi qua Gateway.
#
# Yêu cầu đang chạy: Identity (8081), Catalog (8082), Gateway (8080) dùng cùng .env.
# Admin: Identity chỉ cấp ROLE_ADMIN cho user ID có trong ADMIN_USER_IDS lúc khởi động.
#   1. Lần đầu: chạy với -RegisterAdmin để tạo tài khoản admin, script in ra userId rồi dừng.
#   2. Khởi động lại Identity với ADMIN_USER_IDS=<userId>.
#   3. Chạy lại script với -AdminEmail/-AdminPassword.
#
# Ví dụ:
#   .\scripts\catalog-smoke.ps1 -RegisterAdmin -AdminEmail admin@mooncake.local -AdminPassword 'Admin@123456'
#   .\scripts\catalog-smoke.ps1 -AdminEmail admin@mooncake.local -AdminPassword 'Admin@123456'
param(
    [string]$GatewayUrl = 'http://localhost:8080',
    [string]$CatalogUrl = 'http://localhost:8082',
    [Parameter(Mandatory = $true)][string]$AdminEmail,
    [Parameter(Mandatory = $true)][string]$AdminPassword,
    [switch]$RegisterAdmin
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$client = New-Object System.Net.Http.HttpClient
$gateway = $GatewayUrl.TrimEnd('/')
$catalog = $CatalogUrl.TrimEnd('/')
$script:passed = 0

# Chuỗi tiếng Việt viết bằng escape để file chạy đúng trên PowerShell 5.1 (đọc .ps1 không BOM theo ANSI).
function U([string]$s) { [regex]::Unescape($s) }

function Invoke-Api {
    param([string]$Method, [string]$Url, $Body = $null, [string]$Token = $null, [hashtable]$Headers = @{})
    $request = New-Object System.Net.Http.HttpRequestMessage ([System.Net.Http.HttpMethod]::new($Method)), $Url
    if ($Token) { $request.Headers.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue 'Bearer', $Token }
    foreach ($key in $Headers.Keys) { [void]$request.Headers.TryAddWithoutValidation($key, $Headers[$key]) }
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Depth 10 -Compress
        $request.Content = New-Object System.Net.Http.StringContent $json, ([Text.Encoding]::UTF8), 'application/json'
    }
    $response = $client.SendAsync($request).GetAwaiter().GetResult()
    $bytes = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
    $text = [Text.Encoding]::UTF8.GetString($bytes)
    $parsed = $null
    if ($text.Trim().StartsWith('{') -or $text.Trim().StartsWith('[')) { $parsed = $text | ConvertFrom-Json }
    [pscustomobject]@{ Status = [int]$response.StatusCode; Json = $parsed; Text = $text }
}

function Expect($result, [int[]]$status, [string]$label) {
    if ($result.Status -notin $status) {
        throw "FAIL: $label -> HTTP $($result.Status), mong doi $($status -join '/'). Body: $($result.Text)"
    }
    $script:passed++
    Write-Host ("PASS  {0,-62} HTTP {1}" -f $label, $result.Status)
    return $result.Json
}

function Assert([bool]$condition, [string]$label) {
    if (-not $condition) { throw "FAIL: $label" }
    $script:passed++
    Write-Host "PASS  $label"
}

function Login([string]$email, [string]$password) {
    (Expect (Invoke-Api POST "$gateway/api/auth/login" @{ email = $email; password = $password }) 200 "login $email").accessToken
}

function New-Suffix { [guid]::NewGuid().ToString('N').Substring(0, 8) }

# ─── Chờ Gateway + Identity + Catalog sẵn sàng ─────────────────────────────────
$ready = $false
for ($i = 0; $i -lt 45; $i++) {
    try {
        $probe = Invoke-Api GET "$gateway/api/categories"
        if ($probe.Status -eq 200) { $ready = $true; break }
    } catch { }
    Start-Sleep -Seconds 2
}
if (-not $ready) { throw 'Gateway/Catalog chua san sang sau 90 giay.' }

# ─── Bước khởi tạo admin (chỉ chạy một lần) ────────────────────────────────────
if ($RegisterAdmin) {
    $registered = Invoke-Api POST "$gateway/api/auth/register" @{ fullName = 'MoonCake Admin'; email = $AdminEmail; password = $AdminPassword }
    if ($registered.Status -eq 409) { Write-Host "Admin $AdminEmail da ton tai." }
    else { [void](Expect $registered 201, 200 'register admin account') }
    $adminLogin = Expect (Invoke-Api POST "$gateway/api/auth/login" @{ email = $AdminEmail; password = $AdminPassword }) 200 'login admin'
    Write-Host ''
    Write-Host "ADMIN userId = $($adminLogin.user.userId)"
    Write-Host "Khoi dong lai Identity voi ADMIN_USER_IDS=$($adminLogin.user.userId) roi chay lai script khong co -RegisterAdmin."
    return
}

$envValues = @{}
Get-Content (Join-Path $PSScriptRoot '..\.env') | Where-Object { $_ -match '^[A-Z_]+=' } | ForEach-Object {
    $k, $v = $_ -split '=', 2; $envValues[$k] = $v
}
$commerceHeaders = @{ 'X-Service-Name' = 'commerce'; 'X-Service-Token' = $envValues['COMMERCE_TO_CATALOG_TOKEN'] }
$run = New-Suffix

Write-Host "=== Catalog smoke test (run $run) qua $gateway ==="

# ─── 1. Supplier onboarding + admin duyệt ─────────────────────────────────────
$adminToken = Login $AdminEmail $AdminPassword
$supplierEmail = "tv2-supplier-$run@example.com"
$supplierPassword = "Pw-$run-$(New-Suffix)"
[void](Expect (Invoke-Api POST "$gateway/api/auth/register" @{ fullName = 'TV2 Supplier'; email = $supplierEmail; password = $supplierPassword }) 201, 200 'register supplier user')
$supplierToken = Login $supplierEmail $supplierPassword
$profile = Expect (Invoke-Api POST "$gateway/api/supplier-profile" @{ companyName = "Banh Trung Thu $run"; taxCode = "TAX$run" } $supplierToken) 201, 200 'supplier onboarding (PENDING)'
[void](Expect (Invoke-Api POST "$gateway/api/suppliers/$($profile.supplierId)/verification" @{ decision = 'VERIFIED'; reason = 'smoke test' } $adminToken) 200 'admin VERIFIED supplier')

$strangerEmail = "tv2-stranger-$run@example.com"
[void](Expect (Invoke-Api POST "$gateway/api/auth/register" @{ fullName = 'Stranger'; email = $strangerEmail; password = $supplierPassword }) 201, 200 'register user khac (khong phai owner)')
$strangerToken = Login $strangerEmail $supplierPassword

# ─── 2. Store (TV1) ────────────────────────────────────────────────────────────
$store = Expect (Invoke-Api POST "$gateway/api/stores" @{ name = "TV2 Smoke $run" } $supplierToken) 201 'tao store (DRAFT)'
[void](Expect (Invoke-Api PATCH "$gateway/api/stores/$($store.storeId)" @{ status = 'ACTIVE' } $supplierToken) 200 'store -> ACTIVE')

# ─── 3. Category: seed V3 trên SQL Server, quyền admin ────────────────────────
$categories = Expect (Invoke-Api GET "$gateway/api/categories?size=100") 200 'CA01 GET categories (public)'
$slugs = @($categories.items | ForEach-Object { $_.slug })
Assert (@('truyen-thong', 'hien-dai', 'chay', 'cao-cap', 'thieu-nhi' | Where-Object { $_ -notin $slugs }).Count -eq 0) 'migration V3 da seed du 5 danh muc goc'
$categoryId = ($categories.items | Where-Object { $_.slug -eq 'truyen-thong' }).categoryId
[void](Expect (Invoke-Api POST "$gateway/api/categories" @{ name = 'X'; slug = "x-$run" } $supplierToken) 403 'CA03 user thuong tao category -> 403')
$child = Expect (Invoke-Api POST "$gateway/api/categories" @{ name = "Banh nuong $run"; slug = "banh-nuong-$run"; parentCategoryId = $categoryId } $adminToken) 201 'CA03 admin tao category con'
[void](Expect (Invoke-Api PATCH "$gateway/api/categories/$categoryId" @{ parentCategoryId = $child.categoryId } $adminToken) 400 'CA04 dat cha la con cua no -> 400 (chu trinh)')
[void](Expect (Invoke-Api GET "$gateway/api/categories?size=101") 400 'size=101 -> 400')

# ─── 4. Product + Variant ──────────────────────────────────────────────────────
$productName = (U 'Bánh nướng thập cẩm') + " $run"
$product = Expect (Invoke-Api POST "$gateway/api/products" @{ storeId = $store.storeId; categoryId = $categoryId; name = $productName } $supplierToken) 201 'CA08 tao product (DRAFT)'
Assert ($product.productCode -match '^PRD-[0-9A-F]{8}$') "productCode do server sinh: $($product.productCode)"
[void](Expect (Invoke-Api POST "$gateway/api/products" @{ storeId = $store.storeId; categoryId = $categoryId; name = 'Hack' } $strangerToken) 400, 403 'CA08 user khong phai owner -> tu choi')
[void](Expect (Invoke-Api GET "$gateway/api/products/$($product.productId)") 404 'CA07 public xem product DRAFT -> 404')
[void](Expect (Invoke-Api PATCH "$gateway/api/products/$($product.productId)" @{ status = 'ACTIVE' } $supplierToken) 400 'CA09 ACTIVE khi chua co variant ACTIVE -> 400')

$skuA = "SKU-$run-A"; $skuB = "SKU-$run-B"
$variantA = Expect (Invoke-Api POST "$gateway/api/products/$($product.productId)/variants" @{ sku = $skuA; variantName = 'Hop 2 banh'; basePrice = 180000; stockQuantity = 50 } $supplierToken) 201 'CA13 tao variant A 180.000d'
$variantB = Expect (Invoke-Api POST "$gateway/api/products/$($product.productId)/variants" @{ sku = $skuB; variantName = 'Hop qua'; basePrice = 50000; stockQuantity = 20 } $supplierToken) 201 'CA13 tao variant B 50.000d'
[void](Expect (Invoke-Api POST "$gateway/api/products/$($product.productId)/variants" @{ sku = $skuA; variantName = 'Trung'; basePrice = 1; stockQuantity = 1 } $supplierToken) 409 'CA13 SKU trung -> 409 (UQ tren SQL Server)')
foreach ($v in @($variantA, $variantB)) {
    [void](Expect (Invoke-Api PATCH "$gateway/api/products/$($product.productId)/variants/$($v.variantId)" @{ status = 'ACTIVE' } $supplierToken) 200 "CA14 variant $($v.sku) -> ACTIVE")
}
[void](Expect (Invoke-Api PATCH "$gateway/api/products/$($product.productId)" @{ status = 'ACTIVE' } $supplierToken) 200 'CA09 product -> ACTIVE')

# ─── 5. Search public trên SQL Server (collation thật) ────────────────────────
function Search([string]$q) {
    $r = Expect (Invoke-Api GET "$gateway/api/products?q=$([uri]::EscapeDataString($q))&storeId=$($store.storeId)") 200 "CA06 search q='$q'"
    return @($r.items | Where-Object { $_.productId -eq $product.productId }).Count -eq 1
}
Assert (Search ((U 'nướng thập cẩm'))) 'search co dau tim thay product'
Assert (Search ((U 'BÁNH NƯỚNG'))) 'search khong phan biet hoa thuong (CI)'
$noAccentFound = Search 'banh nuong'
Write-Host ("INFO  search khong dau 'banh nuong' -> {0} (phu thuoc collation cua DB)" -f $(if ($noAccentFound) { 'TIM THAY' } else { 'KHONG tim thay' }))
$detail = Expect (Invoke-Api GET "$gateway/api/products/$($product.productId)") 200 'CA07 public xem product ACTIVE'
Assert ($detail.variants.Count -eq 2) 'CA07 tra 2 variant ACTIVE'
$summary = (Expect (Invoke-Api GET "$gateway/api/products?storeId=$($store.storeId)") 200 'CA06 listing theo store').items | Where-Object { $_.productId -eq $product.productId }
Assert ([decimal]$summary.minPrice -eq 50000) "CA06 minPrice = gia variant ACTIVE thap nhat ($($summary.minPrice))"

# ─── 6. Promotion ──────────────────────────────────────────────────────────────
$now = [DateTime]::UtcNow
$promotionBody = @{
    storeId = $store.storeId; name = "Trung thu $run"
    startAt = $now.AddDays(-1).ToString('yyyy-MM-ddTHH:mm:ss'); endAt = $now.AddDays(30).ToString('yyyy-MM-ddTHH:mm:ss')
    rules = @(
        @{ ruleType = 'PERCENT_DISCOUNT'; targetVariantId = $variantA.variantId; discountPercent = 10 },
        @{ ruleType = 'BUY_X_GET_Y'; targetVariantId = $variantA.variantId; rewardVariantId = $variantB.variantId; minQuantity = 2; rewardQuantity = 1 }
    )
}
$promotion = Expect (Invoke-Api POST "$gateway/api/promotions" $promotionBody $supplierToken) 201 'CA18 tao promotion 10% + mua 2 tang 1 (DRAFT)'
$bad = @{ storeId = $store.storeId; name = 'Sai'; startAt = $promotionBody.startAt; endAt = $promotionBody.endAt; rules = @(@{ ruleType = 'FIXED_DISCOUNT' }) }
[void](Expect (Invoke-Api POST "$gateway/api/promotions" $bad $supplierToken) 400 'CA18 FIXED_DISCOUNT thieu discountAmount -> 400')
[void](Expect (Invoke-Api GET "$gateway/api/promotions/$($promotion.promotionId)") 404 'CA17 public xem promotion DRAFT -> 404')
[void](Expect (Invoke-Api PATCH "$gateway/api/promotions/$($promotion.promotionId)" @{ status = 'ACTIVE' } $supplierToken) 200 'CA19 promotion -> ACTIVE')
$publicPromotions = Expect (Invoke-Api GET "$gateway/api/promotions?storeId=$($store.storeId)") 200 'CA16 GET promotions public theo store'
Assert (@($publicPromotions.items | Where-Object { $_.promotionId -eq $promotion.promotionId }).Count -eq 1) 'CA16 promotion ACTIVE xuat hien public'

# ─── 7. Internal IN06 / IN07 / IN08 (Commerce gọi thẳng Catalog) ──────────────
[void](Expect (Invoke-Api POST "$gateway/internal/v1/variants/snapshots" @{ variantIds = @($variantA.variantId) } -Headers $commerceHeaders) 401, 403, 404 'Gateway khong cho goi /internal/** tu ben ngoai')
[void](Expect (Invoke-Api POST "$catalog/internal/v1/variants/snapshots" @{ variantIds = @($variantA.variantId) }) 401 'IN07 khong co service token -> 401')
$in06 = Expect (Invoke-Api GET "$catalog/internal/v1/products/$($product.productId)/variants/$($variantA.variantId)/snapshot" -Headers $commerceHeaders) 200 'IN06 snapshot variant A'
Assert ([decimal]$in06.agreedUnitPrice -eq 180000) 'IN06 agreedUnitPrice = basePrice'
$in07 = Expect (Invoke-Api POST "$catalog/internal/v1/variants/snapshots" @{ variantIds = @($variantB.variantId, $variantA.variantId, $variantB.variantId) } -Headers $commerceHeaders) 200 'IN07 snapshot theo lo (B, A, B)'
Assert ($in07.Count -eq 2 -and $in07[0].variantId -eq $variantB.variantId -and $in07[1].variantId -eq $variantA.variantId) 'IN07 giu thu tu va gop ID trung'
Assert ($in07[1].productCode -eq $product.productCode -and $in07[1].storeStatus -eq 'ACTIVE') 'IN07 co productCode va storeStatus'

$calc = Expect (Invoke-Api POST "$catalog/internal/v1/promotions/calculate" @{ storeId = $store.storeId; lines = @(@{ variantId = $variantA.variantId; quantity = 2 }, @{ variantId = $variantB.variantId; quantity = 1 }) } -Headers $commerceHeaders) 200 'IN08 tinh khuyen mai (2 A + 1 B)'
# A: 2 x 180.000 = 360.000, giảm 10% = 36.000. B: mua 2 A tặng 1 B = 50.000. Tổng giảm 86.000.
Assert ([decimal]$calc.subtotal -eq 410000) "IN08 subtotal = 410000 ($($calc.subtotal))"
Assert ([decimal]$calc.discountTotal -eq 86000) "IN08 discountTotal = 86000 ($($calc.discountTotal))"
Assert ([decimal]$calc.totalAmount -eq 324000) "IN08 totalAmount = 324000 ($($calc.totalAmount))"
[void](Expect (Invoke-Api POST "$catalog/internal/v1/promotions/calculate" @{ storeId = ($store.storeId + 999999); lines = @(@{ variantId = $variantA.variantId; quantity = 1 }) } -Headers $commerceHeaders) 400 'IN08 variant khac store -> 400')

# ─── 8. Tồn kho: hold IN12 → availableQuantity (IN07) và chặn giảm tồn dưới hold (CA14) ─
$holdKey = "smoke-$run"
$hold = @{ reservationKey = $holdKey; expiresAt = [DateTimeOffset]::UtcNow.AddMinutes(15).ToString('o')
           lines = @(@{ variantId = $variantA.variantId; quantity = 5 }) }
[void](Expect (Invoke-Api POST "$catalog/internal/v1/inventory/reservations" $hold -Headers $commerceHeaders) 200 'IN12 giu 5 hop A (stock 50)')
$afterHold = Expect (Invoke-Api POST "$catalog/internal/v1/variants/snapshots" @{ variantIds = @($variantA.variantId) } -Headers $commerceHeaders) 200 'IN07 sau khi giu hang'
Assert ($afterHold[0].stockQuantity -eq 50 -and $afterHold[0].availableQuantity -eq 45) "IN07 stockQuantity=50, availableQuantity=45 ($($afterHold[0].stockQuantity)/$($afterHold[0].availableQuantity))"
$variantUrl = "$gateway/api/products/$($product.productId)/variants/$($variantA.variantId)"
[void](Expect (Invoke-Api PATCH $variantUrl @{ stockQuantity = 3 } $supplierToken) 409 'CA14 giam ton xuong 3 < 5 dang giu -> 409')
[void](Expect (Invoke-Api PATCH $variantUrl @{ stockQuantity = 5 } $supplierToken) 200 'CA14 dat ton = 5 (bang luong dang giu) -> 200')
[void](Expect (Invoke-Api PUT "$catalog/internal/v1/inventory/reservations/$holdKey/release" -Headers $commerceHeaders) 200 'IN12 release hold')
$afterRelease = Expect (Invoke-Api POST "$catalog/internal/v1/variants/snapshots" @{ variantIds = @($variantA.variantId) } -Headers $commerceHeaders) 200 'IN07 sau khi release'
Assert ($afterRelease[0].availableQuantity -eq 5) "IN07 availableQuantity tro lai 5 ($($afterRelease[0].availableQuantity))"

Write-Host ''
Write-Host "PASS ALL: $script:passed kiem tra. Du lieu test: store $($store.storeId), product $($product.productId) ($($product.productCode))."
