# MSS Mooncake Marketplace Backend

Repository hiện tại chỉ chứa backend Maven multi-module và chưa có mã FE/Vite.

Skeleton này giữ đúng kiến trúc trong project spec: 4 business microservices độc lập và 1 API Gateway. Root chỉ là Maven parent/aggregator, không phải Spring Boot application và không sở hữu business data.

## Kiến trúc và ownership

| Thành phần | Thư mục hiện tại | Port | Database ownership |
|---|---|---:|---|
| API Gateway | `API-Gateway` | 8080 | Không có business DB |
| Identity Service | `IdentityService` | 8081 | `mss_identity` |
| Catalog & Store Service | `CatalogStoreService` | 8082 | `mss_catalog_store` |
| Commerce Service | `CommerceService` | 8083 | `mss_commerce` |
| RFQ & Negotiation Service | `RFQService` | 8084 | `mss_rfq_negotiation` |

Tên thư mục hiện tại được giữ nguyên để không phá scaffold có sẵn; artifact/service names dùng tên chuẩn trong tài liệu (`identity-service`, `catalog-store-service`, `commerce-service`, `rfq-negotiation-service`).

Business ownership:

- Identity: `APP_USER`, `CUSTOMER_PROFILE`, `SUPPLIER_PROFILE`, `ADDRESS`.
- Catalog & Store: `STORE`, `CATEGORY`, `PRODUCT`, `PRODUCT_VARIANT`, `PROMOTION`, `PROMOTION_RULE`.
- Commerce: `CART`, `CART_ITEM`, `CUSTOMER_ORDER`, `DELIVERY_ADDRESS`, `SELLER_ORDER`, `ORDER_ITEM`, `PAYMENT`, `CANCELLATION`, `REFUND`, `SHIPMENT`, `DISPUTE`, `SETTLEMENT`, `REVIEW`.
- RFQ & Negotiation: `RFQ`, `QUOTATION`, `QUOTATION_ITEM`, `CONVERSATION`, `CONVERSATION_MESSAGE`.

Không có `ROLE` entity conceptual, `RFQ_ITEM`, `RFQ_RECIPIENT`, hoặc service thứ sáu. TV3, TV5, TV6 đều làm trong Commerce Service.

## Source layout

Các business service dùng mô hình Spring Boot 3 lớp truyền thống:

- `controller`: HTTP adapter, validation và response DTO.
- `service` / `service/impl`: business flow và transaction boundary.
- `repository`: Spring Data JPA access.
- `entity`: JPA persistence model.
- `dto/request`, `dto/response` và `dto/internal`: API contracts, không expose entity.
- `client` / `client/dto`: OpenFeign/REST contracts cho service khác.
- `config`, `security`, `enums`, `websocket`: supporting code theo từng service.

Mỗi business service có `application.yml` và `db/migration` riêng. API Gateway chỉ có cấu hình route, không sở hữu database hoặc Flyway migration. Không dùng Clean Architecture/Hexagonal package (`application`, `domain`, `infrastructure`) trong source hiện tại.

Các entity hiện mới là skeleton, chưa có đầy đủ mapping/constraints/business logic. Không import entity/repository/code implementation từ service khác và không tạo FK/join xuyên database. Cross-service dùng logical ID, snapshot và DTO contract.

## API Gateway routes

Frontend chỉ gọi Gateway:

- `/api/auth/**`, `/api/users/**`, `/api/addresses/**`, `/api/supplier-profile/**`, `/api/admin/**` -> Identity :8081.
- `/api/stores/**`, `/api/products/**`, `/api/categories/**`, `/api/promotions/**` -> Catalog & Store :8082.
- `/api/cart/**`, `/api/checkout/**`, `/api/orders/**`, `/api/payments/**`, `/api/cancellations/**`, `/api/refunds/**`, `/api/shipments/**`, `/api/disputes/**`, `/api/settlements/**`, `/api/reviews/**` -> Commerce :8083.
- `/api/rfqs/**`, `/api/quotations/**`, `/api/conversations/**`, `/api/messages/**` -> RFQ & Negotiation :8084.

Upstream URL override: `IDENTITY_SERVICE_URL`, `CATALOG_SERVICE_URL`, `COMMERCE_SERVICE_URL`, `RFQ_SERVICE_URL`. `/api/supplier-profile/**` có route riêng tới Identity. Gateway cho phép CORS từ `CORS_ALLOWED_ORIGINS` (mặc định `http://localhost:5173`; danh sách phân cách dấu phẩy). Repo backend hiện không có mã FE/Vite; FE nên gọi Gateway, không gọi trực tiếp các service. Internal summary v1 contracts nằm trong [`docs/contracts/README.md`](docs/contracts/README.md).

## Synchronous communication

- Commerce gọi Identity và Catalog qua OpenFeign để lấy summary/snapshot.
- RFQ gọi Identity, Catalog và Commerce qua OpenFeign; accept quotation dùng contract tạo order idempotent.
- Các flow TV3/TV5/TV6 cùng nằm trong Commerce nên gọi application service Java nội bộ, không dùng Feign.
- Timeout, retry policy, error mapping, auth giữa service và idempotency chi tiết còn là TODO; không retry mù cho command có side effect.
- Kafka chưa được thêm vào MVP.

## Authentication

Identity phát access token JWT ký HS256. Gateway và cả bốn business service xác minh chữ ký, `iss`, `aud` và `exp`; API nghiệp vụ yêu cầu Bearer token. Token dùng chung cấu hình `JWT_SECRET` (tối thiểu 32 byte), `JWT_ISSUER` (mặc định `mooncake-identity`) và `JWT_AUDIENCE` (mặc định `mooncake-api`). Không có secret mặc định trong runtime; đặt các biến này trong môi trường chạy hoặc `.env` không commit trước khi chạy Docker Compose.

Token hiện có thời hạn 24 giờ và chứa `userId`, `customerId`, `roles`; Identity phát `ROLE_CUSTOMER` sau khi đăng nhập thành công. HS256 cần cùng secret tại các app để kiểm tra token; đây là lựa chọn MVP, cần giữ secret ngoài source control và thay secret khi bị lộ.

PowerShell local có thể tạo secret ngẫu nhiên cho phiên hiện tại bằng:

```powershell
$env:JWT_SECRET = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$env:CATALOG_TO_IDENTITY_TOKEN = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$env:COMMERCE_TO_IDENTITY_TOKEN = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$env:RFQ_TO_IDENTITY_TOKEN = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$env:COMMERCE_TO_CATALOG_TOKEN = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$env:RFQ_TO_CATALOG_TOKEN = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$env:RFQ_TO_COMMERCE_TOKEN = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
```

Với Docker Compose, đặt cùng giá trị `JWT_SECRET` trong file `.env` tại repository root (không commit file đó). Khi chạy từng service trực tiếp, đặt biến môi trường trong terminal trước khi chạy Maven Wrapper.

Mỗi cặp gọi service dùng một secret riêng, truyền bằng `X-Service-Name` và `X-Service-Token`. Khai báo sáu biến `CATALOG_TO_IDENTITY_TOKEN`, `COMMERCE_TO_IDENTITY_TOKEN`, `RFQ_TO_IDENTITY_TOKEN`, `COMMERCE_TO_CATALOG_TOKEN`, `RFQ_TO_CATALOG_TOKEN`, `RFQ_TO_COMMERCE_TOKEN`; mỗi secret ngẫu nhiên tối thiểu 32 ký tự và không dùng lại giữa các cặp. Provider chỉ gán authority đúng cho caller đã xác thực. Danh sách admin cấu hình qua `ADMIN_USER_IDS`, là danh sách `user_id` đã được tạo sẵn, phân cách bằng dấu phẩy; Identity chỉ gắn `ROLE_ADMIN` vào token của các ID này. Không có API tự cấp quyền admin.

Supplier onboarding bắt đầu ở `PENDING`. Admin xem hồ sơ bằng `GET /api/admin/suppliers` và duyệt bằng `PATCH /api/admin/suppliers/{supplierId}/verification` với `{"verificationStatus":"VERIFIED","reason":"..."}` hoặc `REJECTED` kèm lý do. Lịch sử lưu ở `supplier_verification_audit` và đọc qua `GET /api/admin/suppliers/{supplierId}/verification-history`. Supplier `VERIFIED` mới được tạo/cập nhật store; admin có thể chỉnh trạng thái store để quản trị. Chuyển trạng thái cho phép `PENDING → VERIFIED/REJECTED`, `VERIFIED → SUSPENDED`, `SUSPENDED → VERIFIED`; supplier sửa hồ sơ bị `REJECTED` sẽ gửi lại về `PENDING`, còn chỉnh sửa hồ sơ `VERIFIED` cũng yêu cầu duyệt lại.

## Database and configuration

Mỗi business service có datasource SQL Server và Flyway location riêng. V1 Flyway migration hiện đã được đồng bộ theo bộ physical SQL Server v1; các migration tiếp theo phải chỉ bổ sung/điều chỉnh sau khi team cập nhật physical source of truth.

Mặc định local: Identity `14331/mss_identity`, Catalog `14332/mss_catalog_store`, Commerce `14333/mss_commerce`, RFQ `14334/mss_rfq_negotiation`. Đổi connection bằng `*_DB_URL`, `*_DB_USERNAME`, `*_DB_PASSWORD`. `ddl-auto` là `none`; migration là nguồn tạo schema.

## Chạy từng service

Yêu cầu Java 21 và Maven Wrapper:

```powershell
.\mvnw.cmd -DskipTests compile
.\mvnw.cmd -pl API-Gateway spring-boot:run
.\mvnw.cmd -pl IdentityService spring-boot:run
.\mvnw.cmd -pl CatalogStoreService spring-boot:run
.\mvnw.cmd -pl CommerceService spring-boot:run
.\mvnw.cmd -pl RFQService spring-boot:run
```

Swagger/OpenAPI: `http://localhost:<port>/swagger-ui.html`; docs: `http://localhost:<port>/v3/api-docs`.

## Test và team workflow

```powershell
.\mvnw.cmd test
```

Service tests dùng H2 test profile để smoke-test context và JPA entity scanning; không thay thế integration test với SQL Server. Gateway test là smoke unit test vì Gateway MVC tạo HTTP loopback client trong context, không phù hợp sandbox hạn chế loopback.

Branch: `feature/tv1-account-store`, `feature/tv2-catalog-promo`, `feature/tv3-cart-order`, `feature/tv4-rfq`, `feature/tv5-finance`, `feature/tv6-fulfillment`. Trước khi đổi contract cross-service, cập nhật DTO/OpenAPI và chốt với provider/consumer. Mỗi PR cần migration đúng database owner, backend tests, frontend happy path và cập nhật contract.

Chi tiết phân công, dependencies, acceptance tests và điểm chưa rõ nằm trong [`TODO.md`](TODO.md). Contract inventory nằm trong [`docs/contracts/README.md`](docs/contracts/README.md).

Quy tắc địa chỉ: địa chỉ đầu tiên tự thành mặc định; mỗi customer có tối đa một mặc định; chọn địa chỉ khác sẽ thay mặc định hiện tại; không thể bỏ mặc định nếu chưa chọn địa chỉ thay thế; xóa mặc định thì địa chỉ có ID nhỏ nhất còn lại được thăng cấp. Store mới bắt đầu DRAFT, mã có dạng STR- + 12 ký tự hex viết hoa; chỉ ACTIVE được hiển thị công khai. Owner đổi DRAFT → ACTIVE/INACTIVE và ACTIVE ↔ INACTIVE; chỉ admin được chuyển vào/ra SUSPENDED.
