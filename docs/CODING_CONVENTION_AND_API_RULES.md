# MSS — Coding Convention & API Rules

Ngày đối chiếu code: **13/10/2026**. Đối tượng: TV1–TV6, backend, frontend và người review PR.

Đây là **bản thiết kế contract để triển khai đủ chức năng MVP**, không phải xác nhận tất cả API đã hoạt động. Những quy ước mới phải được áp dụng cùng provider, Feign consumer, frontend và test; không đổi response của API đã có một cách âm thầm.

Đọc nhanh: quy tắc code ở mục 3, HTTP/security ở mục 4–5; TV1 ở mục 6, TV2 ở mục 7, TV3 ở mục 8, TV4 ở mục 9, TV5–TV6 ở mục 10; DTO/Feign ở mục 11, flow lỗi ở mục 12, cách chuyển từ code hiện tại ở mục 13.

## 1. Nguồn chuẩn, phạm vi và cách đọc

Nguồn nghiệp vụ: `MSS_Mooncake_Marketplace_Project_Spec_v3_Microservices.docx`, `MSS_4Service_6Member_Workplan_and_Scaffold_v3.md`. Phân công: [TODO.md](../TODO.md), [TODO_IMPLEMENTATION.md](../TODO_IMPLEMENTATION.md). Cột, kiểu, khóa và ownership theo physical SQL Server mới nhất và migration của từng service.

Thứ tự xử lý khác biệt:

1. Yêu cầu mới của nhóm: 4 business services + Gateway, mô hình 3 lớp.
2. Physical DB mới thay phần cấu trúc/ownership cũ: `SUPPLIER_PROFILE` thuộc Identity, `STORE` thuộc Catalog.
3. Spec gốc quyết định nghiệp vụ; convention này quyết định cách biểu diễn API. Code đang chạy là bằng chứng về hiện trạng, không tự biến mọi behavior hiện có thành business rule.
4. Migration V2 Identity hiện thêm `password_hash` để phục vụ native login. Ghi nhận thay đổi này khi cập nhật physical model; không coi DB hiện tại chỉ có V1.

**Đính chính TODO cũ:** mục 3 trong spec cho phép **một RFQ accept một hoặc nhiều quotation** khi buyer chia nhu cầu cho nhiều supplier. Không đóng RFQ hoặc reject mọi quotation còn lại chỉ vì vừa accept một quotation. Cách gộp các lần accept vào customer order và giới hạn phân bổ quantity vẫn cần chốt.

Ký hiệu trong danh mục API:

| Nhãn | Ý nghĩa |
|---|---|
| `C` | Đã có controller và service logic trong repo; chưa khẳng định đã test end-to-end hay đáp ứng convention mới. |
| `S` | Đã có route nhưng implementation còn skeleton/TODO. |
| `N` | API thiết kế bổ sung cho chức năng trong spec, chưa có controller tương ứng. |
| `T` | Contract phụ thuộc policy/provider/quyền chưa chốt; chỉ triển khai sau khi ghi rõ quyết định. |
| `K` | Command yêu cầu `Idempotency-Key` theo quy ước mục 5. |

Các mã `ID01`, `CA01`… dùng để trao đổi task/test; không phải URL hay `operationId`. Mọi cột request/response của API `C` là contract đích nếu khác code, xem mục 13 trước khi tích hợp.

## 2. Service ownership và phân công

| Service/module | Port | DB | Owner |
|---|---:|---|---|
| `API-Gateway` | 8080 | Không có | Người phụ trách hạ tầng phối hợp cả nhóm |
| `IdentityService` | 8081 | `mss_identity` | TV1 |
| `CatalogStoreService` | 8082 | `mss_catalog_store` | TV1 store, TV2 catalog/promotion |
| `CommerceService` | 8083 | `mss_commerce` | TV3 order, TV5 finance, TV6 fulfillment |
| `RFQService` | 8084 | `mss_rfq_negotiation` | TV4 |

| Thành viên | Entity phải bao phủ | Frontend feature |
|---|---|---|
| TV1 | `APP_USER`, `CUSTOMER_PROFILE`, `SUPPLIER_PROFILE`, `ADDRESS`, `STORE` | `account`, `supplier-store` |
| TV2 | `CATEGORY`, `PRODUCT`, `PRODUCT_VARIANT`, `PROMOTION`, `PROMOTION_RULE` | `catalog-promotion` |
| TV3 | `CART`, `CART_ITEM`, `CUSTOMER_ORDER`, `DELIVERY_ADDRESS`, `SELLER_ORDER`, `ORDER_ITEM` | `cart-order` |
| TV4 | `RFQ`, `QUOTATION`, `QUOTATION_ITEM`, `CONVERSATION`, `CONVERSATION_MESSAGE` | `rfq-negotiation` |
| TV5 | `PAYMENT`, `CANCELLATION`, `REFUND`, `DISPUTE`, `SETTLEMENT` | `payment-settlement` |
| TV6 | `SHIPMENT`, `REVIEW`; phối hợp seller-order state với TV3 | `fulfillment` |

28 bảng nghiệp vụ không tương ứng 28 CRUD controller. `DELIVERY_ADDRESS`, `ORDER_ITEM`, nghĩa vụ payment và settlement được tạo qua flow; không mở API để client tùy ý sửa snapshot hay số tiền.

## 3. Coding convention

### 3.1. Backend Java và mô hình 3 lớp

Luồng xử lý: `Controller → Service/ServiceImpl → Repository → DB của service`.

- Controller: parse path/query/body, `@Valid`, principal, HTTP status và DTO. Không gọi repository, tính tiền, đặt transaction nghiệp vụ hoặc gọi Feign trực tiếp.
- Service: kiểm tra actor/ownership, trạng thái, business rule, mapping và transaction. Service có thể gọi service khác **trong cùng application** hoặc Feign client của service ngoài.
- Repository: truy vấn dữ liệu local, có giới hạn/phân trang; dùng parameter binding. Không chứa HTTP/provider call, không đọc DB service khác.
- Giữ package root hiện tại: `mss.mooncake.identity`, `catalogstore`, `commerce`, `rfqnegotiation`, `gateway`. Package con: `controller`, `service`, `service.impl`, `repository`, `entity`, `dto.request`, `dto.response`, `dto.internal`, `client`, `client.dto`, `exception`, `security`, `config`, `enums`, `websocket` khi cần.
- Java 21, Maven, UTF-8, 4 spaces; một top-level public type/file, không wildcard imports, không gom nhiều method trên một dòng. Format theo cùng cấu hình IDE của nhóm; không reformat file ngoài feature.
- Constructor injection, dependency `private final`; không field `@Autowired`. Reuse service/helper đã có; không tạo generic base controller/service/repository chỉ để giảm vài dòng.
- DTO ưu tiên `record`, request và response tách biệt. Không trả entity JPA, password/hash, token nội bộ hoặc raw provider payload ra client.
- Entity dùng `Long`, `BigDecimal`, enum lưu STRING và mapping đúng DDL. Không áp các cột audit/base entity lên bảng không có cột đó. Không thêm `@Version` trước khi có migration tương ứng.
- Quan hệ ngoài service dùng `...IdRef` ở entity; không `@ManyToOne` xuyên DB. DTO mới dùng tên nghiệp vụ như `supplierId`; DTO cũ đã dùng `supplierIdRef` giữ tương thích đến khi chuyển contract.
- Validation biên request + kiểm tra nghiệp vụ ở service + constraint DB cùng bảo vệ dữ liệu. `@Size` không thay `@NotBlank`; update không cho chuỗi trắng vào field bắt buộc.
- Không bắt `Exception` rồi trả `null`, `false` hoặc HTTP 200. Exception handler tập trung theo từng service; lỗi constraint được chuyển thành lỗi API có ý nghĩa, không lộ SQL/stack trace.
- Tiền dùng `BigDecimal`, không `double`/`float`, không `new BigDecimal(double)`. Rounding/currency là contract nghiệp vụ cần chốt; không tự giả định mọi số tiền đều làm tròn về số nguyên.
- Timestamp dùng UTC; API có offset/Z, ngày giao là `LocalDate`. SQL `DATETIME2(0)` không lưu timezone: quy ước persisted timestamp là UTC và mapping phải nhất quán.
- `@Transactional` trên public service method được gọi qua Spring proxy; không trông chờ self-invocation tạo transaction. Read flow có thể dùng `readOnly=true`.
- Local transaction bảo vệ một DB. Không giữ lock/transaction dài khi chờ mạng/provider; cross-service flow dùng idempotency, ghi trạng thái bền vững và đối soát theo mục 12.
- Dùng projection/fetch phù hợp để tránh N+1; không gọi Feign từng item trong một vòng lặp lớn. Batch contract có giới hạn khi checkout cần nhiều variant.
- Log có trace/request ID, service, operation và resource ID cần thiết. Không log password, JWT, secret, toàn bộ địa chỉ/điện thoại hay thông tin thanh toán.

### 3.2. Đặt tên thống nhất

| Thành phần | Quy tắc | Ví dụ |
|---|---|---|
| Class/interface/record | PascalCase, tên miền nghiệp vụ | `OrderController`, `OrderApplicationService`, `OrderApplicationServiceImpl` |
| Entity/repository | Hậu tố hiện dùng trong repo | `ProductEntity`, `ProductRepository` |
| Request mới | Động tác + đối tượng + `Request` | `CreateProductRequest`, `UpdateProductRequest`, `AcceptQuotationRequest` |
| Response | Đối tượng + `Response`; list item riêng khi cần | `ProductResponse`, `OrderSummaryResponse` |
| Method/variable | lowerCamelCase, hành động rõ | `createOrderFromQuotation`, `sellerOrderId` |
| Boolean | Câu hỏi/trạng thái rõ | `isDefault`, `hasNext`, `eligible` |
| Enum/constant/error code | UPPER_SNAKE_CASE | `BUY_X_GET_Y`, `QUOTATION_EXPIRED` |
| Feign client | Tên provider + `ServiceClient` | `CatalogServiceClient`; không tạo client thứ hai trùng chức năng |
| SQL | snake_case theo physical; FK/ref rõ | `seller_order`, `supplier_id_ref` |
| Migration | `V<next>__<snake_case_description>.sql` | `V3__add_refresh_session.sql` chỉ nếu feature/session được chốt |
| OpenAPI operationId | lowerCamelCase, duy nhất toàn API catalog | `identityGetMyProfile`, `commerceCheckout`, `rfqAcceptQuotation` |
| React component/page | PascalCase | `OrderDetailPage.jsx`, `QuotationCard.jsx` |
| React hook | `use` + PascalCase | `useOrderDetail` |
| FE API module | camelCase + `Api` | `orderApi.js`; nếu dùng TS thì `.ts` |
| Test | Đối tượng + `Test`; method mô tả behavior | `SettlementServiceTest`, `shouldBlockPayoutWhenDisputeIsOpen` |

Các tên hiện có như `AddressRequest`, `SupplierProfileRequest`, `StoreService`, `IdentityClient` là ngoại lệ tương thích. Đừng tạo một bộ tên mới song song chỉ để đổi hậu tố.

### 3.3. Frontend, database và Git

- FE ở `../MoonCakeMarketFrontend`; chỉ một API client dùng Gateway base URL từ env. Component gọi module feature API/hook; không hard-code port 8081–8084.
- Repo FE hiện là JS/JSX; workplan đề nghị TypeScript. Convention tên áp dụng cả hai; việc chuyển TS cần một thay đổi đồng bộ riêng. Không trộn `.js/.ts` cho cùng contract khi chưa thống nhất build.
- Dùng error `code` để quyết định UI, không so sánh chuỗi `message`. Có loading, empty, validation và retry phù hợp; disable submit giúp UX nhưng không thay idempotency backend.
- UI chỉ hiển thị amount/status trả từ server; không tự quyết định giá cuối, refund, payout hay quyền dựa trên việc ẩn button. Không render chat/description bằng raw HTML không kiểm soát.
- Input label, lỗi theo field, keyboard navigation và focus sau lỗi phải dùng được. Token/PII không vào URL hoặc log; strategy lưu token/refresh session cần được nhóm chốt.
- Không sửa migration đã áp dụng. V2 Identity đã tồn tại: lấy số migration tiếp theo thực sự trống; không copy ví dụ version một cách máy móc. SQL Server là hệ kiểm thử constraint chính.
- `docs/sqlserver/*` hiện chỉ bootstrap database; bảng do Flyway tạo. Không chạy thủ công V1 lên DB đã có Flyway history.
- Branch theo flow TV1–TV6, PR backend/FE liên kết nhau. Chỉ commit file của task; không commit `.env`, private key, token, `target`, `node_modules`. `.env.example` chỉ có placeholder.
- Sửa contract phải kèm OpenAPI, provider, consumer DTO, FE và contract tests. Migration được owner DB review; không tạo shared JPA module hoặc service mới.

## 4. API rule chung

### 4.1. URL, method, version

Public base hiện tại là `/api`, đi qua Gateway; internal là `/internal/v1`. Giữ public URL đang dùng để tránh phá frontend. Khi có breaking contract chưa thể migrate đồng thời, phát hành version riêng như `/api/v2` và kế hoạch deprecation; không tự đổi toàn bộ hiện tại sang `/api/v1` chỉ vì tài liệu mới.

- Resource collection dùng danh từ số nhiều, lowercase, nhiều từ dùng kebab-case: `/api/products`, `/api/seller-orders`. Ngoại lệ singleton hiện có: `/api/cart`, `/api/supplier-profile`; auth và checkout giữ tên đang dùng.
- Path variable có nghĩa rõ: `{productId}`, `{sellerOrderId}`. Query và JSON dùng lowerCamelCase. Không dùng `/getAllProducts`, `/addProduct`, URL tên bảng hoặc tên service.
- GET đọc; POST tạo hoặc thực thi command; PUT thay toàn bộ representation đã công bố; PATCH cập nhật một phần; DELETE loại bỏ resource theo policy. Command có kiểm tra trạng thái dùng POST như `/quotations/{quotationId}/accept`.
- Không dùng GET body hoặc GET để thanh toán, hủy đơn, accept, payout. Không sửa status giao dịch qua một generic `PATCH {status: ...}` bỏ qua use case.
- Nested path phải xác minh quan hệ cha-con, không chỉ lookup ID con. Command cùng tài nguyên dùng cùng tên, không tạo `/accept`, `/approve` và `/confirm` cho cùng một hành vi.

Semantics method/status theo [HTTP Semantics — RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html). Cách đặt URL, pagination và DTO dưới đây là lựa chọn nhất quán của MSS, không phải yêu cầu bắt buộc của HTTP.

### 4.2. JSON, ID, money, ngày giờ và PATCH

Contract đích mới:

| Dữ liệu | Biểu diễn |
|---|---|
| ID SQL BIGINT | JSON string thập phân, ví dụ `"123"`; Java parse `Long`, FE giữ opaque string. Tránh mất chính xác khi vượt giới hạn integer an toàn của JavaScript. |
| Money/percent decimal | JSON decimal string: `"250000.00"`, `"5.00"`; Java `BigDecimal`. Precision/scale không vượt cột DB. |
| Quantity, version, page/size | JSON integer, có min/max validation. |
| Date | `"2026-10-20"`; không tự gắn timezone vào ngày giao. |
| Timestamp | ISO-8601 UTC, ví dụ `"2026-10-13T08:30:00Z"`; V1 lưu precision giây. |
| Enum | UPPER_SNAKE_CASE, đúng contract/schema; client không tự gửi status server quản lý. |
| Null | Chỉ dùng khi nullable; không biến null thành chuỗi rỗng hay số 0. |

DTO hiện tại có `Long`, `BigDecimal` và `LocalDateTime` nên có thể serialize numeric/no-offset. **Đây là điểm cần migrate có phối hợp**, không phải mô tả response hiện tại. Không đổi serializer global rồi để FE/Feign cũ tự xử lý.

POST/PUT thường dùng `application/json`. PATCH đích dùng `application/merge-patch+json`: field vắng giữ nguyên, null yêu cầu xóa giá trị nullable, field có giá trị thì thay; array được thay cả mảng. Validate kết quả cuối và allowlist field được sửa, không merge trực tiếp vào entity. Nguồn: [JSON Merge Patch — RFC 7396](https://www.rfc-editor.org/rfc/rfc7396.html).

Các PATCH record hiện tại chưa phân biệt field vắng và null. Chỉ công bố semantics Merge Patch sau khi có presence-aware parsing và test; trước đó OpenAPI phải mô tả behavior thực tế. Empty patch, unknown/read-only field hoặc null cho field bắt buộc trả lỗi validation.

### 4.3. Response, pagination và tìm kiếm

Success trả DTO trực tiếp, không bọc thêm `success/message/data` mặc định. Create trả `201` cùng `Location` khi có URL resource; command hoàn thành trả DTO với `200`; delete trả `204` không body. Command chưa xong chỉ trả `202` khi có durable operation và cách đọc kết quả, không dùng 202 để che exception.

Danh sách có thể tăng lớn dùng `PageResponse<T>`. Quy ước MSS: `page` từ 0, mặc định `size=20`, tối đa 100; vượt giới hạn trả `400`, không âm thầm cắt request. Sort allowlist theo API và có ID làm tie-breaker. Không trả Spring `Page`/entity serialization trực tiếp.

```json
{
  "items": [],
  "page": 0,
  "size": 20,
  "totalElements": 0,
  "totalPages": 0
}
```

Query mẫu: `/api/products?q=thap%20cam&storeId=12&categoryId=3&page=0&size=20&sort=name,asc`. Mỗi endpoint ghi rõ filter hỗ trợ; sort/filter lạ trả `400`. `mine=true` yêu cầu đăng nhập; không hiểu `supplierId=...` là bằng chứng quyền sở hữu. Giá min/max theo variant cần TV2 ghi rõ semantics trước khi hỗ trợ.

Address list hiện có trả array nhỏ; chỉ giữ dạng array nếu có giới hạn được công bố. Store list hiện có chưa phân trang: phải chuyển contract cùng FE trước khi dùng cho dữ liệu lớn. Chat dùng cursor riêng ở mục 9.

### 4.4. Lỗi và mã HTTP

Error contract đích dùng `application/problem+json`, các field chuẩn và extension của MSS theo [Problem Details — RFC 9457](https://www.rfc-editor.org/rfc/rfc9457.html):

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "Quotation has expired.",
  "instance": "/api/quotations/81/accept",
  "code": "QUOTATION_EXPIRED",
  "traceId": "example-trace-id"
}
```

Validation bổ sung `errors: [{"field":"items[0].quantity","code":"POSITIVE_REQUIRED","message":"Quantity must be positive."}]`. Không đưa giá trị nhạy cảm đã nhập vào lỗi. FE dịch theo `code`; `detail` chỉ để hỗ trợ người dùng.

| HTTP | Dùng trong MSS |
|---:|---|
| 200 / 201 / 204 | Đọc/command xong; tạo resource; xóa thành công không body. |
| 202 | Command bền vững đang xử lý, có operation/status URL. |
| 400 | JSON, path/query, field, pagination hoặc payload không hợp lệ. |
| 401 | Thiếu/sai/hết hạn credential; Bearer challenge khi phù hợp. |
| 403 | Đã xác thực nhưng thiếu capability. |
| 404 | Resource không tồn tại hoặc private resource không thuộc caller. |
| 409 | Duplicate, sai state, quotation hết hạn, idempotency conflict, insufficient stock. |
| 412 | `If-Match` không còn đúng khi endpoint hỗ trợ conditional update. |
| 415 | Content-Type không hỗ trợ. |
| 429 | Rate limit; trả hướng dẫn retry phù hợp. |
| 500 | Lỗi ngoài dự kiến; detail không lộ nội bộ. |
| 502 / 503 / 504 | Upstream lỗi response; service không sẵn sàng; timeout upstream. |

Ví dụ stable code: `VALIDATION_FAILED`, `RESOURCE_NOT_FOUND`, `ACCESS_DENIED`, `EMAIL_ALREADY_EXISTS`, `INVALID_STATE_TRANSITION`, `INSUFFICIENT_STOCK`, `QUOTATION_EXPIRED`, `QUOTATION_VERSION_CONFLICT`, `IDEMPOTENCY_CONFLICT`, `SETTLEMENT_BLOCKED`, `UPSTREAM_TIMEOUT`.

ErrorResponse hiện tại của Identity/Catalog có `timestamp/status/error/message/path/validationErrors`; phải migrate FE và exception/security handlers trước khi thay. Không trả hai schema lỗi khác nhau giữa controller và authentication filter trên cùng contract mới.

## 5. Quyền, idempotency và concurrency

Các nhãn quyền sau là capability nghiệp vụ, **không mặc định tương ứng claim role đã implement**:

| Nhãn | Actor và điều kiện |
|---|---|
| `PUBLIC` | Chỉ dữ liệu đã được công bố; không lộ draft, PII, quotation riêng hoặc finance. |
| `USER` | User hợp lệ, chỉ dữ liệu của chính mình. |
| `BUYER` | Có customer context, sở hữu cart/RFQ/order tương ứng. |
| `SUPPLIER` | Có supplier context, sở hữu store/seller order/quotation tương ứng. |
| `PARTICIPANT` | Buyer/supplier đúng conversation hoặc giao dịch đó. |
| `ADMIN*` | Quyền quản trị chuyên biệt; mapping capability, audit và authority phải chốt trước. |
| `SERVICE` | Caller service được xác thực và allowlist operation; không phải request public tự thêm header. |
| `PROVIDER*` | Provider xác thực qua signature/mTLS theo adapter đã chọn; không dùng JWT người mua thay signature webhook. |

- Gateway và service phải enforce auth thích hợp; URL private/internal không đủ bảo mật. Consumer xác minh user/customer/supplier ID đúng ngữ cảnh, không coi các ID này là cùng một ID.
- Lấy actor từ principal. Body/filter có ID chỉ định đối tượng, không cấp quyền. Supplier xem seller order của mình, không nhận cả đơn nhiều store với payment/supplier khác.
- Nội bộ ưu tiên token audience/scope theo service hoặc cơ chế service identity được chốt. Không chia private signing key cho tất cả service; nếu chuyển sang chữ ký bất đối xứng thì resource service chỉ cần public key. Không coi shared-secret JWT hiện có là thiết kế phân quyền hoàn tất.
- TLS khi deploy, secret qua env/secret store; allowlist CORS/origin; không tắt CSRF tùy tiện nếu dùng cookie auth. Chặn mass assignment, injection và việc server fetch URL người dùng tùy ý.
- `K`: header idempotency do caller giữ qua retry; scope gồm actor/service + operation + resource + key. Lưu payload fingerprint và kết quả bền vững, cùng transaction local với tác động; key trùng payload khác trả `409`. Authenticate/authorize cả khi replay.
- Key cùng payload đã hoàn tất trả cùng resource/kết quả; đang xử lý trả contract pending đã công bố, không chạy lại side effect. TTL/retention và bảng lưu key là quyết định kỹ thuật cần migration, không giả định V1 đã hỗ trợ.
- Mỗi accepted quotation chỉ tạo tối đa một seller order. Ràng buộc nghiệp vụ này tồn tại ngoài TTL idempotency; hiện `quotation_id_ref` chỉ có index, cần uniqueness/ledger phù hợp sau khi kiểm tra dữ liệu và chốt physical update.
- Webhook dedup bằng provider event/transaction identity; unique `transaction_ref` hiện tại không thay toàn bộ xử lý retry/out-of-order. Refund/payout cần provider idempotency key và đối soát sau timeout.
- Giá/tồn kho read trước commit có race. TV2–TV3 phải chốt atomic stock update/reservation và compensation. Không gọi read quantity rồi decrement mù; không retry POST với key mới sau timeout.
- Update cạnh tranh dùng state check + lock/conditional update phù hợp trong DB; ETag/`If-Match` chỉ công bố nếu thực sự implement. `quotationVersion` là version nghiệp vụ, không thay khóa chống cập nhật đồng thời của mọi entity.

## 6. API TV1 — Account, Supplier, Store

Owner Identity cho `ID*`; owner Catalog cho `ST*`. `—` nghĩa không có body. `Page<X>` là schema ở mục 4.3; field sets mô tả ở mục 11.

| Mã / hiện trạng | Method + path | Quyền | Request/query | Success | Điều kiện chính |
|---|---|---|---|---|---|
| ID01 C | `POST /api/auth/register` | PUBLIC | `RegisterRequest` | 201 `UserSummaryResponse` | Normalize email nhất quán; hash password; unique email; không tự cấp supplier/admin. |
| ID02 C | `POST /api/auth/login` | PUBLIC | `LoginRequest` | 200 `AuthResponse` | Sai credentials trả thông báo chung; rate limit; không log password/token. |
| ID03 C | `GET /api/users/me` | USER | — | 200 `UserProfileResponse` | User từ principal. |
| ID04 C | `PATCH /api/users/me` | USER | `UpdateProfileRequest` | 200 `UserProfileResponse` | Allowlist fullName/phone; không sửa role/status qua profile. |
| ID05 C | `GET /api/addresses` | BUYER | — | 200 `AddressResponse[]` | Chỉ địa chỉ của customer hiện tại; contract giới hạn list cần chốt. |
| ID06 C | `GET /api/addresses/{addressId}` | BUYER | — | 200 `AddressResponse` | Owner check. |
| ID07 C | `POST /api/addresses` | BUYER | `AddressRequest` | 201 `AddressResponse` | customerId từ principal; rule default phải nhất quán. |
| ID08 C | `PATCH /api/addresses/{addressId}` | BUYER | `UpdateAddressRequest` | 200 `AddressResponse` | Bao gồm đổi default; xử lý concurrent update trong service. |
| ID09 C | `DELETE /api/addresses/{addressId}` | BUYER | — | 204 | Không xóa/sửa delivery snapshot trong đơn cũ. |
| ID10 C | `POST /api/supplier-profile` | USER | `SupplierProfileRequest` | 201 `SupplierProfileResponse` | Unique user/taxCode; verification do server quản lý. |
| ID11 C | `GET /api/supplier-profile` | USER | — | 200 `SupplierProfileResponse` | Profile của user; 404 nếu chưa onboarding. |
| ID12 C | `PATCH /api/supplier-profile` | SUPPLIER | `UpdateSupplierProfileRequest` | 200 `SupplierProfileResponse` | Không cho self-approve verification. |
| ST01 C | `GET /api/stores` | PUBLIC / SUPPLIER khi mine | `mine`, `supplierId`, page/sort đích | 200 `Page<StoreResponse>` đích | Public chỉ store được công bố; code hiện trả array. |
| ST02 C | `GET /api/stores/{storeIdentifier}` | PUBLIC / owner | Numeric ID hoặc slug | 200 `StoreResponse` | Hiện hỗ trợ ID/slug; slug chỉ gồm số có thể mơ hồ, cần chốt validation khi tạo. |
| ST03 C | `POST /api/stores` | SUPPLIER | `CreateStoreRequest` | 201 `StoreResponse` | Supplier lấy qua Identity; code/slug do server sinh theo rule đã chốt. |
| ST04 C | `PATCH /api/stores/{storeId}` | SUPPLIER | `UpdateStoreRequest` | 200 `StoreResponse` | Owner; branding/theme; status chỉ transition được phép. |
| ST05 T | `DELETE /api/stores/{storeId}` | SUPPLIER | — | 204 hoặc 409 | CRUD trong spec; chốt archive/hard-delete/retention. Không cascade mất catalog và lịch sử giao dịch. |

Auth đã có native JWT + password hash; repo chưa có refresh/session/revocation workflow. Các API bổ trợ nếu nhóm cần vòng đời tài khoản đầy đủ:

| Mã / hiện trạng | Method + path | Quyền | Request/query | Success | Điều kiện chính |
|---|---|---|---|---|---|
| AU01 T | `POST /api/auth/refresh` | Refresh credential | Session/refresh credential theo thiết kế | 200 `AuthResponse` | Chốt token storage, rotation/reuse detection và migration; không dùng access token hết hạn làm refresh token. |
| AU02 T | `POST /api/auth/logout` | Session credential | Session hiện tại | 204 | Chỉ hứa revoke khi server có revoke/session mechanism; xóa token FE không vô hiệu JWT đã bị lấy cắp. |
| AU03 T | `POST /api/auth/password-change` | USER | oldPassword, newPassword | 204 | Xác thực lại, policy password và revoke-session strategy. |
| AU04 T | `POST /api/auth/password-reset-requests` | PUBLIC | email | 202 generic | Cần email provider + token reset một lần có expiry; không tiết lộ email tồn tại. |
| AU05 T | `POST /api/auth/password-resets` | Reset credential | resetToken, newPassword | 204 | Token một lần, không nhận userId làm quyền reset. |

AU01–AU05 là phần mở rộng cần chốt cơ chế, không phải các API đã có hoặc yêu cầu MVP được tự động thêm vào.

## 7. API TV2 — Catalog, Search, Promotion

Owner Catalog. Search dùng query trên `/api/products`; đây là canonical API cho chức năng `/search` trong TODO, tránh hai search endpoint cùng làm một việc.

| Mã / hiện trạng | Method + path | Quyền | Request/query | Success | Điều kiện chính |
|---|---|---|---|---|---|
| CA01 N | `GET /api/categories` | PUBLIC | parentCategoryId, page/sort | 200 `Page<CategoryResponse>` | Không trả cây vô hạn; chọn parent qua query. |
| CA02 N | `GET /api/categories/{categoryId}` | PUBLIC | — | 200 `CategoryResponse` | Chỉ dữ liệu public. |
| CA03 T | `POST /api/categories` | ADMIN* | `CreateCategoryRequest` | 201 `CategoryResponse` | Chốt ai quản trị category dùng chung; unique slug. |
| CA04 T | `PATCH /api/categories/{categoryId}` | ADMIN* | category mutable fields | 200 `CategoryResponse` | Không tạo chu trình parent; validate slug/status. |
| CA05 T | `DELETE /api/categories/{categoryId}` | ADMIN* | — | 204 hoặc 409 | Chốt retention; có child/product thì không cascade tự động. |
| CA06 N | `GET /api/products` | PUBLIC / SUPPLIER khi mine | q, storeId, categoryId, mine, page/sort | 200 `Page<ProductSummaryResponse>` | Public không thấy draft/hidden; owner được xem phần của mình. |
| CA07 N | `GET /api/products/{productId}` | PUBLIC / owner | — | 200 `ProductResponse` | Product + display variant/promotion theo projection, không lộ dữ liệu private store. |
| CA08 N | `POST /api/products` | SUPPLIER | `CreateProductRequest` | 201 `ProductResponse` | Own store; category hợp lệ; unique store/productCode. |
| CA09 N | `PATCH /api/products/{productId}` | SUPPLIER | product mutable fields | 200 `ProductResponse` | Không đổi owner; rename/code policy chốt trước; lịch sử dùng snapshot. |
| CA10 T | `DELETE /api/products/{productId}` | SUPPLIER | — | 204 hoặc 409 | Policy archive/delete phải rõ; không xóa snapshot ở Commerce/RFQ. |
| CA11 N | `GET /api/products/{productId}/variants` | PUBLIC / owner | page/sort | 200 `Page<VariantResponse>` | Variants của product đúng quyền. |
| CA12 N | `GET /api/products/{productId}/variants/{variantId}` | PUBLIC / owner | — | 200 `VariantResponse` | Verify variant.productId. |
| CA13 N | `POST /api/products/{productId}/variants` | SUPPLIER | `CreateVariantRequest` | 201 `VariantResponse` | Unique SKU; price/stock không âm. |
| CA14 N | `PATCH /api/products/{productId}/variants/{variantId}` | SUPPLIER | variant mutable fields | 200 `VariantResponse` | Không reparent; stock update có concurrency strategy. |
| CA15 T | `DELETE /api/products/{productId}/variants/{variantId}` | SUPPLIER | — | 204 hoặc 409 | Chốt archive/delete và xử lý local promotion FK, logical refs ở service khác. |
| CA16 N | `GET /api/promotions` | PUBLIC / SUPPLIER khi mine | storeId, mine, page/sort | 200 `Page<PromotionResponse>` | Public chỉ promotion hợp lệ được công bố. |
| CA17 N | `GET /api/promotions/{promotionId}` | PUBLIC / owner | — | 200 `PromotionResponse` | Response có rules. |
| CA18 N | `POST /api/promotions` | SUPPLIER | `CreatePromotionRequest` gồm rules | 201 `PromotionResponse` | Own store; startAt < endAt; rule fields đúng loại. |
| CA19 N | `PATCH /api/promotions/{promotionId}` | SUPPLIER | name/startAt/endAt/status cho phép | 200 `PromotionResponse` | Không sửa storeId; không bypass state policy. |
| CA20 N | `PUT /api/promotions/{promotionId}/rules` | SUPPLIER | `PromotionRuleRequest[]` | 200 `PromotionResponse` | Thay toàn bộ rules atomically; verify target/reward thuộc phạm vi store. |
| CA21 T | `DELETE /api/promotions/{promotionId}` | SUPPLIER | — | 204 hoặc 409 | Chốt retention; không thay giá đơn đã mua. |

Không tạo coupon code hoặc `QUANTITY_DISCOUNT` ngoài spec. Promotion preview cho giỏ đi qua Commerce `/api/cart/pricing`, dùng Catalog promotion engine; không tin giá/discount do browser gửi.

## 8. API TV3 — Cart, Checkout, Customer Order, Seller Order

Owner Commerce. `orderId` là master order; `sellerOrderId` là phần đơn của một store; hai ID không hoán đổi.

| Mã / hiện trạng | Method + path | Quyền | Request/query | Success | Điều kiện chính |
|---|---|---|---|---|---|
| OR01 N | `GET /api/cart` | BUYER | — | 200 `CartResponse` | Đọc giỏ hiện tại; không tạo persistent giỏ chỉ vì GET. |
| OR02 N,K | `POST /api/cart/items` | BUYER | variantId, quantity | 201 `CartItemResponse` nếu mới; 200 nếu cập nhật | Unique cart/variant; rule cộng quantity hay thay cần chốt; replay không cộng hai lần. |
| OR03 N | `PATCH /api/cart/items/{cartItemId}` | BUYER | quantity | 200 `CartItemResponse` | Quantity là giá trị tuyệt đối > 0; owner; xóa dùng DELETE. |
| OR04 N | `DELETE /api/cart/items/{cartItemId}` | BUYER | — | 204 | Không tác động order đã checkout. |
| OR05 N | `DELETE /api/cart/items` | BUYER | — | 204 | Xóa giỏ hiện tại của chính caller. |
| OR06 N | `POST /api/cart/pricing` | BUYER | Các lựa chọn shipping đã chốt, nếu có | 200 `CartPricingResponse` | Tính preview server-side, không tạo order/giữ giá; checkout vẫn revalidate. |
| OR07 N,K | `POST /api/checkout` | BUYER | `CheckoutRequest` | 201 `OrderResponse` | Đọc Identity/Catalog; 1 master + seller orders + address/items snapshots trong local transaction. |
| OR08 N | `GET /api/orders` | BUYER | status, sourceType, page/sort | 200 `Page<OrderSummaryResponse>` | Chỉ master orders của buyer. |
| OR09 N | `GET /api/orders/{orderId}` | BUYER | — | 200 `OrderResponse` | Snapshot cả đơn của buyer, không dựa catalog hiện tại. |
| OR10 N | `GET /api/seller-orders` | SUPPLIER | storeId, status, page/sort | 200 `Page<SellerOrderSummaryResponse>` | Chỉ store thuộc supplier; Gateway cần thêm route. |
| OR11 N | `GET /api/seller-orders/{sellerOrderId}` | BUYER / SUPPLIER | — | 200 `SellerOrderResponse` | Buyer của đơn hoặc supplier của phần đơn; projection theo actor. |
| OR12 T,K | `POST /api/seller-orders/{sellerOrderId}/prepare` | SUPPLIER | — | 200 `SellerOrderResponse` | TV3+TV5+TV6 chốt payment eligibility và state trước PREPARING; gọi Java nội bộ. |

Không mở POST `/api/orders` để FE gửi một order với tổng tiền tùy ý. Order retail chỉ qua checkout; order RFQ qua accept và internal contract. Không có API sửa trực tiếp `ORDER_ITEM`/`DELIVERY_ADDRESS` snapshot sau khi đặt hàng.

## 9. API TV4 — Public RFQ, Quotation, Negotiation/Chat

Owner RFQ. Public RFQ không đồng nghĩa mọi quotation và chat trong RFQ là public.

| Mã / hiện trạng | Method + path | Quyền | Request/query | Success | Điều kiện chính |
|---|---|---|---|---|---|
| RQ01 N | `GET /api/rfqs` | PUBLIC / BUYER khi mine | q, mine, status, page/sort | 200 `Page<RfqSummaryResponse>` | Public chỉ bài đã công bố; supplier xem nhu cầu, không thấy PII/chat riêng. |
| RQ02 N | `GET /api/rfqs/{rfqId}` | PUBLIC / owner | — | 200 `RfqResponse` | Draft chỉ owner; không kèm báo giá riêng vào public response. |
| RQ03 N | `POST /api/rfqs` | BUYER | `CreateRfqRequest` | 201 `RfqResponse` | Quantity > 0; không tạo RFQ_ITEM/RFQ_RECIPIENT; customer lấy principal. |
| RQ04 T | `PATCH /api/rfqs/{rfqId}` | BUYER | rfq mutable fields | 200 `RfqResponse` | Chốt được sửa gì sau khi đã nhận quotation/accept; không làm đổi giao dịch đã accept. |
| RQ05 T,K | `POST /api/rfqs/{rfqId}/publish` | BUYER | — | 200 `RfqResponse` | Chốt có lưu draft hay create là publish; nếu create publish thì không triển khai endpoint thừa. |
| RQ06 T,K | `POST /api/rfqs/{rfqId}/close` | BUYER | reason nếu policy cần | 200 `RfqResponse` | Chốt điều kiện đóng; không auto-close sau accept đầu tiên. |
| RQ07 T,K | `POST /api/rfqs/{rfqId}/cancel` | BUYER | reason | 200 `RfqResponse` | Không tự hủy seller order đã hình thành; order cancellation là flow Commerce. |
| RQ08 N | `GET /api/rfqs/{rfqId}/quotations` | BUYER owner / SUPPLIER | page/sort | 200 `Page<QuotationSummaryResponse>` | Buyer compare các báo giá của RFQ; supplier chỉ thấy báo giá của mình trừ policy được chốt khác. |
| RQ09 N,K | `POST /api/rfqs/{rfqId}/quotations` | SUPPLIER | `CreateQuotationRequest` | 201 `QuotationResponse` | Own store; RFQ nhận quotation; server cấp version và tính tổng; submission/draft behavior phải chốt. |
| RQ10 N | `GET /api/quotations` | SUPPLIER | mine=true, rfqId, storeId, status, page/sort | 200 `Page<QuotationSummaryResponse>` | Dashboard báo giá của supplier, không đọc toàn sàn. |
| RQ11 N | `GET /api/quotations/{quotationId}` | BUYER owner / SUPPLIER owner | — | 200 `QuotationResponse` | Full terms/items của version cụ thể; không expose cho đối thủ. |
| RQ12 T | `PATCH /api/quotations/{quotationId}` | SUPPLIER owner | draft quotation fields/items | 200 `QuotationResponse` | Chỉ draft theo policy; đã submit thì dùng revision, accepted bất biến. |
| RQ13 T,K | `POST /api/quotations/{quotationId}/submit` | SUPPLIER owner | — | 200 `QuotationResponse` | Cần khi có draft workflow; validate đầy đủ item/terms/validity. |
| RQ14 N,K | `POST /api/quotations/{quotationId}/revisions` | SUPPLIER owner | `ReviseQuotationRequest` + expectedVersion | 201 `QuotationResponse` | Tạo row/version mới, previousQuotationId server cấp; không sửa accepted trade. |
| RQ15 S,K | `POST /api/quotations/{quotationId}/accept` | BUYER RFQ owner | `AcceptQuotationRequest` | 200 `QuotationAcceptedResponse` khi hoàn tất | Path ID authoritative; accept đúng version/expiry; gọi Commerce; xem mục 12. |
| RQ16 T,K | `POST /api/quotations/{quotationId}/reject` | BUYER RFQ owner | reason | 200 `QuotationResponse` | Quyền/state/reason persistence cần chốt; không reject accepted order bằng endpoint này. |
| RQ17 T,K | `POST /api/quotations/{quotationId}/cancel` | SUPPLIER owner | reason | 200 `QuotationResponse` | Chốt withdrawal trước accept; không bypass cancellation/refund Commerce. |
| RQ18 N | `GET /api/quotations/{quotationId}/order` | BUYER / SUPPLIER owner | — | 200 `QuotationOrderLinkResponse` | Recovery sau timeout: orderId, sellerOrderId, conversionStatus khi đã biết; 404 nếu chưa có conversion. |
| CH01 N | `POST /api/rfqs/{rfqId}/conversations` | BUYER / SUPPLIER | supplierId chỉ khi buyer chọn bên trao đổi | 201 `ConversationResponse` mới; 200 nếu đã có | Buyer của RFQ; supplier lấy chính mình; unique rfq/supplier chống tạo trùng. |
| CH02 N | `GET /api/conversations` | PARTICIPANT | rfqId, page/sort | 200 `Page<ConversationResponse>` | Chỉ hội thoại của caller. |
| CH03 N | `GET /api/conversations/{conversationId}` | PARTICIPANT | — | 200 `ConversationResponse` | Membership kiểm tra ở service. |
| CH04 N | `GET /api/conversations/{conversationId}/messages` | PARTICIPANT | beforeMessageId hoặc afterMessageId, limit | 200 `MessagePageResponse` | Cursor không trộn hai chiều; limit 1..100, mặc định 50; ID làm thứ tự ổn định. |

So sánh quotation dùng RQ08 + RQ11, không cần endpoint `/compare` nếu không có phép tính mới. QUOTATION_ITEM tạo/cập nhật atomically cùng quotation draft/revision, không mở CRUD để sửa item của accepted quotation.

WebSocket/STOMP contract đích:

| Kênh | Hiện trạng | Payload và rule |
|---|---|---|
| `WS /ws/chat` | Có endpoint/broker config; chưa có đầy đủ handler/security | Gateway phải có proxy hỗ trợ WebSocket; browser mở qua Gateway; auth CONNECT, allowlist origin, không token trong query URL. |
| `SEND /app/conversations/{conversationId}/messages` | N | `SendMessageRequest {clientMessageId, messageType, content}`; sender lấy principal; validate membership và kích thước. |
| `SUBSCRIBE /topic/conversations/{conversationId}` | N | Chỉ participant; kiểm tra SUBSCRIBE phía server, không chỉ kiểm tra SEND. |
| Broadcast | N | `MessageResponse {messageId, conversationId, senderUserId, messageType, content, sentAt, clientMessageId}` sau commit DB. |

MessagePageResponse gồm `items`, `nextCursor`, `hasMore`; response ghi rõ sắp xếp theo messageId. Reconnect dùng history API và dedup messageId. `clientMessageId` giúp correlation; muốn dedup gửi lặp sau mất mạng phải có unique/persistent mapping qua migration, V1 chưa có cột này. Chưa có read receipt, attachment upload hoặc exactly-once delivery trong physical; không hứa các tính năng đó. Broker memory hiện tại cần giới hạn một RFQ instance hoặc thiết kế broker chung khi scale.

## 10. API TV5–TV6 — Finance và Fulfillment

### 10.1. TV5: Payment, Cancel/Refund, Dispute, Settlement

Owner Commerce. Nghiệp vụ payment/settlement chạy theo **sellerOrderId**. Cột `K` là yêu cầu thiết kế, không chứng minh provider/DB hiện đã dedup.

| Mã / hiện trạng | Method + path | Quyền | Request/query | Success | Điều kiện chính |
|---|---|---|---|---|---|
| FI01 N | `GET /api/payments` | BUYER / SUPPLIER / ADMIN* | sellerOrderId, paymentType, status, page/sort | 200 `Page<PaymentResponse>` | Lọc quyền ở server; supplier chỉ nhận projection cần thiết. |
| FI02 N | `GET /api/payments/{paymentId}` | BUYER / SUPPLIER / ADMIN* | — | 200 `PaymentResponse` | Owner/authority; không raw provider secret/payment credentials. |
| FI03 T,K | `POST /api/payments/{paymentId}/initiate` | BUYER | provider, allowlisted return target | 200 `PaymentInitiationResponse` | Dùng obligation đã có; server quyết amount; provider adapter quyết redirect/checkout session. |
| FI04 T | `POST /api/payments/{provider}/callback` | PROVIDER* | Raw signed payload | ACK theo provider | Verify signature/event/amount/currency/order binding; durable dedup trước ACK; không nhận status FE. |
| FI05 T | `POST /api/cancellations/preview` | BUYER / SUPPLIER / ADMIN* | sellerOrderId, reason | 200 `CancellationPreviewResponse` | Evaluate actor/time/state/policy; preview không giữ quyền refund cố định. |
| FI06 T,K | `POST /api/cancellations` | BUYER / SUPPLIER / ADMIN* | sellerOrderId, reason | 201 `CancellationResponse` | Recheck policy tại commit; actor/refundPercent server quyết; V1 một cancellation/seller order. |
| FI07 N | `GET /api/cancellations` | BUYER / SUPPLIER / ADMIN* | sellerOrderId, status, page/sort | 200 `Page<CancellationResponse>` | History trong scope caller. |
| FI08 N | `GET /api/cancellations/{cancellationId}` | BUYER / SUPPLIER / ADMIN* | — | 200 `CancellationResponse` | Không lộ lý do/PII của đơn khác. |
| FI09 N | `GET /api/refunds` | BUYER / SUPPLIER / ADMIN* | paymentId, cancellationId, status, page/sort | 200 `Page<RefundResponse>` | History theo quyền payment/order. |
| FI10 N | `GET /api/refunds/{refundId}` | BUYER / SUPPLIER / ADMIN* | — | 200 `RefundResponse` | Trả số tiền/status đã lưu. |
| FI11 T,K | `POST /api/refunds` | ADMIN* | `CreateRefundRequest` | 201 `RefundResponse` pending | Chỉ khi authority cho manual refund được chốt; không vượt paid minus refunds/reserved refunds; provider call đối soát riêng. |
| FI12 N,K | `POST /api/disputes` | BUYER / SUPPLIER theo policy | sellerOrderId, reason, evidenceUrl? | 201 `DisputeResponse` | Actor server lấy; dispute OPEN chặn settlement theo spec. |
| FI13 N | `GET /api/disputes` | PARTICIPANT / ADMIN* | sellerOrderId, status, page/sort | 200 `Page<DisputeResponse>` | Admin queue cần authority; participant chỉ giao dịch mình. |
| FI14 N | `GET /api/disputes/{disputeId}` | PARTICIPANT / ADMIN* | — | 200 `DisputeResponse` | Evidence không public. |
| FI15 T,K | `POST /api/disputes/{disputeId}/resolve` | ADMIN* | `ResolveDisputeRequest` | 200 `DisputeResponse` | Chốt outcome/authority, refund/deduction; cập nhật finance atomically hoặc durable workflow. |
| FI16 N | `GET /api/seller-orders/{sellerOrderId}/settlement-eligibility` | SUPPLIER / ADMIN* | — | 200 `SettlementEligibilityResponse` | Đọc delivered/accepted/payment/dispute trong Commerce; đọc không trigger payout. |
| FI17 N | `GET /api/settlements` | SUPPLIER / ADMIN* | sellerOrderId, status, page/sort | 200 `Page<SettlementResponse>` | Supplier chỉ store của mình. |
| FI18 N | `GET /api/settlements/{settlementId}` | SUPPLIER / ADMIN* | — | 200 `SettlementResponse` | ID ở path là settlementId; tìm theo sellerOrderId dùng query FI17. |
| FI19 T,K | `POST /api/settlements` | ADMIN* hoặc job nội bộ | sellerOrderId | 201 `SettlementResponse` | Một settlement/seller order; tính eligibility và fee snapshot 5%; chốt manual vs scheduled trigger. |
| FI20 T,K | `POST /api/settlements/{settlementId}/payout` | ADMIN* hoặc job nội bộ | — | 200 `SettlementResponse` processing | Recheck blocking dispute + đủ điều kiện; provider idempotency; response này không đồng nghĩa đã PAID. |

FULL được tạo trong retail order flow; DEPOSIT sau quotation ACCEPTED; REMAINING theo terms, due = ngày giao đã thỏa thuận trừ 3 ngày. Không có API public cho buyer tự tạo obligation/amount. Callback refund/payout phải được adapter provider công bố: có thể dùng FI04 nếu provider dùng chung event webhook; nếu tách endpoint thì review Gateway/security trước.

Zero deposit/remaining khi phần trăm là 0 hoặc 100 cần chốt cách bỏ qua obligation zero vì `PAYMENT.amount > 0`. Thời điểm trong ngày/múi giờ của remaining due và trường hợp ngày giao quá gần cũng chưa được spec quyết định. Không tự thu tiền sớm hay sửa terms để hợp constraint.

`HELD/RELEASE` là business requirement; physical V1 chưa có `hold_status`. Settlement V1 cũng chưa có cột deduction snapshot riêng. Chốt cách lưu state/base/deductions trước triển khai finance; không thêm field vào response như thể đã persist, không cộng/trừ refund hai lần.

### 10.2. TV6: Shipment, Delivery, Acceptance, Review

Owner Commerce, phối hợp TV3 cho seller-order state và TV5 cho payment/settlement.

| Mã / hiện trạng | Method + path | Quyền | Request/query | Success | Điều kiện chính |
|---|---|---|---|---|---|
| FU01 N | `GET /api/shipments` | BUYER / SUPPLIER / ADMIN* | sellerOrderId, status, page/sort | 200 `Page<ShipmentResponse>` | Chỉ shipment của phần đơn có quyền. |
| FU02 N | `GET /api/shipments/{shipmentId}` | BUYER / SUPPLIER / ADMIN* | — | 200 `ShipmentResponse` | Tracking/expected/actual dates, không raw provider credentials. |
| FU03 N,K | `POST /api/shipments` | SUPPLIER | `CreateShipmentRequest` | 201 `ShipmentResponse` | Own seller order và fulfillment eligibility; V1 cho nhiều shipment/seller order. |
| FU04 T | `PATCH /api/shipments/{shipmentId}` | SUPPLIER | plan/provider/tracking fields | 200 `ShipmentResponse` | Chốt field được sửa theo state; không PATCH status=DELIVERED tùy ý. |
| FU05 T,K | `POST /api/shipments/{shipmentId}/ship` | SUPPLIER hoặc actor được chốt | shippedAt, trackingCode? | 200 `ShipmentResponse` | Actual ship time hợp lệ; cập nhật seller-order state qua local service. |
| FU06 T,K | `POST /api/shipments/{shipmentId}/delivered` | Actor delivery được chốt | deliveredAt | 200 `ShipmentResponse` | Xác minh nguồn giao thành công; nhiều shipment không có nghĩa shipment đầu đã giao cả đơn. |
| FU07 T | `POST /api/shipments/providers/{provider}/callback` | PROVIDER* | Raw signed event | ACK theo provider | Chỉ khi dùng provider; validate binding, dedup/out-of-order; không public anonymous cập nhật status. |
| FU08 N,K | `POST /api/seller-orders/{sellerOrderId}/accept` | BUYER | — | 200 `SellerOrderResponse` | Nghiệm thu đúng seller order; persist một lần; thời điểm cho phép/timeout cần chốt. |
| FU09 N | `POST /api/reviews` | BUYER | orderItemId, rating, comment? | 201 `ReviewResponse` | Buyer thực sự mua item; unique orderItem; rating 1..5; eligibility timing cần chốt. |
| FU10 N | `GET /api/reviews` | PUBLIC / BUYER khi mine | variantId, storeId, mine, page/sort | 200 `Page<ReviewResponse>` | Public projection chỉ rating/comment/display name được cho phép; không trả order/address/payment IDs nhạy cảm. |
| FU11 N | `GET /api/reviews/{reviewId}` | PUBLIC / author | — | 200 `ReviewResponse` | Policy visibility và moderation; không lộ dữ liệu đơn mua. |

Review filter lấy variant/store từ order snapshots/local refs; không join trực tiếp Catalog DB. Product-level review tổng hợp qua nhiều variants cần contract riêng, vì V1 ORDER_ITEM không có product_id_ref. Không tự quảng cáo filter productId khi chưa có cách ánh xạ đúng.

### 10.3. Quản trị và các chức năng phụ thuộc policy

Spec có Platform Admin cho moderation, hỗ trợ dispute/refund/settlement. Identity chỉ cấp `ROLE_ADMIN` cho `ADMIN_USER_IDS` đã tồn tại khi khởi động; tài khoản này phải do người vận hành xác minh/provision trước. Không gán role từ body đăng ký.

| Mã / hiện trạng | Method + path | Quyền | Request/query | Success | Điều kiện chính |
|---|---|---|---|---|---|
| AD01 T | `GET /api/users` | ADMIN* | status, page/sort | 200 `Page<AdminUserResponse>` | Moderation queue; projection PII tối thiểu. |
| AD02 T,K | `POST /api/users/{userId}/moderation` | ADMIN* | action, reason | 200 `AdminUserResponse` | Chốt actions/quyền/audit và ảnh hưởng token đã phát. |
| AD03 C | `GET /api/admin/suppliers` | ADMIN | status | 200 `SupplierProfileResponse[]` | Onboarding queue; Gateway route tới Identity. |
| AD04 C | `PATCH /api/admin/suppliers/{supplierId}/verification` | ADMIN | verificationStatus, reason | 200 `SupplierProfileResponse` | Chỉ các transition đã cho phép; lưu audit/reason. |
| AD05 T,K | `POST /api/stores/{storeId}/moderation` | ADMIN* | action, reason | 200 `StoreResponse` | Chốt moderation state, audit; không chuyển ownership. |
| AD06 T,K | `POST /api/products/{productId}/moderation` | ADMIN* | action, reason | 200 `ProductResponse` | Không thay order/quotation snapshots. |
| AD07 T,K | `POST /api/rfqs/{rfqId}/moderation` | ADMIN* | action, reason | 200 `RfqResponse` | Không hủy order đã accept chỉ vì ẩn RFQ. |
| AD08 T,K | `POST /api/reviews/{reviewId}/moderation` | ADMIN* | action, reason | 200 `ReviewResponse` | V1 REVIEW chưa có moderation status/audit; cần physical update trước. |

Dispute resolution, refund và payout dùng FI11/FI15/FI19/FI20 với capability riêng, không tạo một bộ finance API trùng dưới `/admin`. Fee MVP đã chốt 5%; không thêm API đổi fee tùy ý. Nếu cần policy/fee cấu hình theo thời gian phải có version/effective date/snapshot và physical model được duyệt. File upload, dashboard analytics/AI forecasting, unread count, favorites và coupon chưa có contract/DB scope rõ: ghi task bổ sung thay vì coi là MVP hiện hữu.

## 11. DTO và contract dữ liệu

### 11.1. Cách đọc field set

Trong các bảng dưới đây, field không có `?` là bắt buộc cho create; `?` là optional/nullable theo DDL. Riêng field ghi `CHỐT` chưa được phép tự chọn default. Update chỉ nhận allowlist mutable subset, áp dụng PATCH ở mục 4.2. ID/timestamp/status do server quản lý không nhận ngược từ response vào create request.

Field length/nullability lấy từ migration; bảng này là contract nghiệp vụ để trao đổi, không thay thế OpenAPI JSON Schema. Field wire mới tuân thủ mục 4.2; field legacy phải migration đồng bộ.

### 11.2. Request schemas

| Schema | Field input | Validation/ghi chú |
|---|---|---|
| `RegisterRequest` / `LoginRequest` | Register: fullName, email, password, phone?; login: email, password | Có DTO hiện tại; password policy trong code chưa phải cam kết bảo mật cuối. Không normalize password. |
| `UpdateProfileRequest` | fullName?, phone? | Email/status/roles ngoài allowlist. |
| `AddressRequest` / `UpdateAddressRequest` | recipientName, recipientPhone, addressLine, city; ward?, district?, isDefault? | Limit lần lượt 255/30/500/100/100/100 theo DDL; customerId server lấy. Default behavior chốt với TV1. |
| `SupplierProfileRequest` / update | companyName, taxCode | Length 255/100; unique taxCode; update không nhận verificationStatus. |
| `CreateStoreRequest` / update | name; logoUrl?, bannerUrl?, themeKey?; update có status theo policy | Length 255/500/500/100; code/slug/supplier server xác định. |
| `CreateCategoryRequest` | name, slug; parentCategoryId? | Không tạo chu trình; status default CHỐT. |
| `CreateProductRequest` | storeId, categoryId, name; productCode CHỐT client nhập hay server sinh; description? | Không tự sinh thêm basePrice trong PRODUCT: giá ở variant. |
| `CreateVariantRequest` | sku, variantName, basePrice, stockQuantity; imageUrl? | SKU unique; decimal >=0, integer stock >=0; status policy CHỐT. |
| `CreatePromotionRequest` | storeId, name, startAt, endAt, rules[] | startAt < endAt; trạng thái khởi tạo/activate CHỐT. |
| `PromotionRuleRequest` | ruleType; targetVariantId?, rewardVariantId?, minQuantity?, discountPercent?, discountAmount?, rewardQuantity? | Required theo loại: percent cần percent, fixed cần amount, BUY_X_GET_Y cần trigger/reward quantities và target/reward; nullable target cho phạm vi toàn store còn CHỐT. |
| `CheckoutRequest` | addressId; shippingSelections? | Cart/customer lấy principal. Shipping selections có seller/store binding, method và phí do server xác nhận; hình thức chọn shipping CHỐT. Không nhận grandTotal/discount. |
| `CreateRfqRequest` | title, quantity; description?, budgetAmount?, requestedDeliveryDate?, deliveryLocation?, customizationRequirements?, expiresAt? | Quantity >0, budget >=0; publish/draft behavior và required fields trước publish CHỐT. |
| `CreateQuotationRequest` / revision | storeId, depositPercent, validUntil, shippingFee, items[]; leadTimeDays?, proposedDeliveryDate?, shippingMethod?, paymentTerms? | Revision thêm expectedVersion. Giá negotiated do supplier đề xuất, server validate và tính tổng. Ngày giao phải đủ để xác định due trước conversion; quyết định dữ liệu thiếu CHỐT. |
| `QuotationItemRequest` | itemNameSnapshot, quantity, unitPrice; variantId?, skuSnapshot?, discountAmount?, customizationNote? | Custom item được variantId=null; quantity >0; item subtotal server tính. Null variant không có nghĩa tự tạo Catalog product. |
| `AcceptQuotationRequest` | quotationVersion, addressId | Path cung cấp quotationId; buyer principal; store/supplier/price/items lấy từ quotation server. Việc lấy address lúc accept là contract đề xuất để Commerce có DELIVERY_ADDRESS. |
| `SendMessageRequest` | clientMessageId, messageType, content | Allowlist type và max length/rate CHỐT; sender/server sentAt không nhận từ client. |
| `CreateRefundRequest` | paymentId, amount, reason; cancellationId? | ADMIN authority; amount >0, không vượt refundable balance; actor/audit phía server. |
| `ResolveDisputeRequest` | outcome, resolution; remedy? | Enum/outcome, refund/deduction và schema remedy CHỐT; không cho client set settledAt hay payout. |
| `CreateShipmentRequest` | sellerOrderId, shippingMethod; provider?, trackingCode?, expectedShipAt?, expectedDeliveryAt? | Shipping fee lấy/đối chiếu agreed order terms; nếu thay đổi fee phải có policy, không tự tính lại order. |
| `CreateReviewRequest` | orderItemId, rating; comment? | rating 1..5; customer server lấy; physical comment MAX nhưng API phải công bố giới hạn input hữu hạn. |

Promotion và quotation phải thống nhất `discountAmount` là tổng giảm của line hay mỗi unit trước khi implement phép tính. Tổng quotation có bao gồm shipping hay không cũng phải được ghi rõ và test trong OpenAPI; DDL không tự quyết định semantics này.

### 11.3. Response schemas và snapshot

Các nhóm `Summary` chỉ chứa field cho list; `Response` detail bổ sung field cần thiết theo quyền. Field set sau là contract tối thiểu cần thiết, không cho phép gửi tất cả entity properties.

| Schema | Field quan trọng |
|---|---|
| `AuthResponse` | accessToken, tokenType=`Bearer`, expiresIn (giây), user=`UserSummaryResponse`; không thêm refreshToken nếu chưa có cơ chế. |
| `UserSummaryResponse` / `UserProfileResponse` | userId, customerId?, fullName, email, phone?; profile thêm status, createdAt, updatedAt. |
| `AddressResponse` / `AddressSummaryResponse` | addressId, customerId, recipientName, recipientPhone, addressLine, ward?, district?, city; response thêm isDefault. |
| `SupplierProfileResponse` / `SupplierSummaryResponse` | supplierId, userId, companyName, verificationStatus; taxCode chỉ cho consumer có nhu cầu; profile thêm audit timestamps. |
| `StoreResponse` / `StoreSummaryResponse` | storeId, supplierId (legacy supplierIdRef), storeCode, name, slug, logoUrl?, status; detail thêm bannerUrl/themeKey. V1 STORE không có audit timestamps, không tự hứa createdAt/updatedAt persist. |
| `CategoryResponse` | categoryId, parentCategoryId?, name, slug, status. |
| `ProductResponse` | productId, storeId, categoryId, productCode, name, description?, status, createdAt, updatedAt; variants projection khi cần. |
| `VariantResponse` | variantId, productId, sku, variantName, imageUrl?, basePrice, stockQuantity hoặc availability projection theo audience, status. |
| `PromotionResponse` | promotionId, storeId, name, startAt, endAt, status, rules[] gồm promotionRuleId + rule fields. |
| `CartResponse` / `CartItemResponse` | cartId?, items[]; item gồm cartItemId, variantId, quantity, product/variant display và availability; giá preview có thể không sẵn sàng nếu Catalog lỗi. |
| `CartPricingResponse` | lines[], sellerGroups[], subtotal, discountTotal, shippingTotal, grandTotal, evaluatedAt, warnings[]; kết quả preview không cam kết giữ hàng/giá. |
| `OrderResponse` / `OrderSummaryResponse` | orderId, orderNo, sourceType, status, placedAt, subtotal, discountTotal, shippingTotal, grandTotal; detail gồm deliveryAddress snapshot và sellerOrders[]. |
| `SellerOrderResponse` | sellerOrderId, orderId, sellerOrderNo, store/supplier display snapshots, quotationId?, status, subtotal, shippingFee, totalAmount, paymentTerms/deposit/remaining/delivery snapshots, items[]. |
| Order item / delivery address | orderItemId, productCodeSnapshot?, productNameSnapshot, variantNameSnapshot?, skuSnapshot?, imageUrlSnapshot?, variantId?, quantity, unitPrice, discountAmount, subtotal; address gồm recipient/phone/full address snapshot. |
| `RfqResponse` | rfqId, rfqCode, title, description?, quantity, budgetAmount?, requestedDeliveryDate?, deliveryLocation?, customizationRequirements?, status, expiresAt?, createdAt; customer identity projection tùy quyền. |
| `QuotationResponse` | quotationId, rfqId, store/supplier refs, previousQuotationId?, versionNo, totalAmount, depositPercent, validUntil, leadTimeDays?, proposedDeliveryDate?, shippingMethod?, shippingFee, paymentTerms?, status, items[], submittedAt?, acceptedAt?. |
| `QuotationAcceptedResponse` / `QuotationOrderLinkResponse` | quotationId, quotationVersion, conversionStatus, orderId?, sellerOrderId?; chỉ báo completed khi Commerce đã xác nhận bền vững. |
| `ConversationResponse` / message | conversationId, rfqId, participant refs/display cần thiết, status, createdAt; message schema ở mục 9. |
| `PaymentResponse` | paymentId, sellerOrderId, paymentType, amount, dueAt?, provider?, status, paidAt?; hold representation CHỐT, transactionRef chỉ audience cần. |
| `PaymentInitiationResponse` | paymentId, status, provider, checkoutUrl? hoặc provider session fields được allowlist; schema theo provider. |
| `CancellationPreviewResponse` | sellerOrderId, eligible, refundPercent?, refundableAmount?, reasons[], evaluatedAt; phần policy outcome phải chốt. |
| `CancellationResponse` | cancellationId, sellerOrderId, actorType, reason, refundPercent?, status, cancelledAt?; không hứa requestedAt nếu chưa persist trong V1. |
| `RefundResponse` | refundId, paymentId, cancellationId?, amount, reason, status, requestedAt, refundedAt?. |
| `DisputeResponse` | disputeId, sellerOrderId, reason, evidenceUrl?, status, resolution?, openedAt, resolvedAt?; actor data giới hạn theo quyền. |
| `SettlementEligibilityResponse` | sellerOrderId, eligible, blockingReasons[]; không tạo settlement khi đọc. |
| `SettlementResponse` | settlementId, sellerOrderId, grossAmount, feeRate, feeAmount, supplierPayoutAmount, status, settledAt?; deductions snapshot representation CHỐT. |
| `ShipmentResponse` | shipmentId, sellerOrderId, shippingMethod, provider?, trackingCode?, expectedShipAt?, shippedAt?, expectedDeliveryAt?, deliveredAt?, shippingFee, status. |
| `ReviewResponse` | reviewId, rating, comment?, createdAt, authorDisplayName?; author/private projection mới có orderItemId. |

Admin response reuse các projection đã có và chỉ bổ sung field cần cho moderation; không bao giờ bao gồm passwordHash. `required`/`nullable` chính thức cho từng audience phải nằm trong OpenAPI; không dùng một DTO chung rồi serialize mọi thứ cho PUBLIC.

### 11.4. Internal REST/OpenFeign contracts

Internal URL không nằm trong Gateway public route. Identity/Catalog/Commerce đã có service-token authentication cho các route nội bộ hiện tại; mỗi route mới phải khai báo đúng caller role, không dùng `permitAll`.

| Mã / hiện trạng | Method + path | Provider → consumer | Input → output | Ghi chú |
|---|---|---|---|---|
| IN01 C | `GET /api/user/{userId}/summary` | Identity → Commerce/RFQ | userId → UserSummaryResponse | Không thay userId bằng customerId. |
| IN02 C | `GET /api/address/{addressId}/summary` | Identity → Commerce | addressId → AddressSummaryResponse | Caller phải xác minh customerId đúng buyer; không public proxy arbitrary address. |
| IN03 C | `GET /api/supplier/{supplierId}/summary` | Identity → Catalog/Commerce/RFQ | supplierId → SupplierSummaryResponse | Chỉ field cần thiết. |
| IN04 C | `GET /api/supplier/by-user/{userId}/summary` | Identity → Catalog | userId → SupplierSummaryResponse | Lookup supplier của principal để check store owner. |
| IN05 C | `GET /internal/v1/stores/{storeId}/summary` | Catalog → Commerce/RFQ | storeId → StoreSummaryResponse | Snapshot display + supplier binding. |
| IN06 S | `GET /internal/v1/products/{productId}/variants/{variantId}/snapshot` | Catalog → Commerce/RFQ | IDs → ProductSnapshotResponse | Kiểm tra đúng parent; DTO hiện có còn thiếu productCode/image/store/availability. |
| IN07 N | `POST /internal/v1/variants/snapshots` | Catalog → Commerce | variantIds[] → VariantSnapshotResponse[] | Cart chỉ giữ variantId; batch này tránh bắt caller biết productId trước và tránh N+1. Limit kỹ thuật phải công bố; missing items fail rõ, không âm thầm bỏ. |
| IN08 C | `POST /internal/v1/promotions/calculate` | Catalog → Commerce | storeId + lines(variantId,quantity) → PromotionCalculationResult | Catalog quyết price/rules/evaluation time; trả applied rules + line discounts/reward lines; caller không gửi arbitrary price để được tin. |
| IN09 S,K | `POST /internal/v1/orders/from-accepted-quotation` | Commerce ← RFQ | AcceptedQuotationOrderRequest → OrderCreationResponse | Hai phía đã có DTO snapshot; authenticated RFQ caller; conversion hiện 501. Cần dedup quotation, create 201/replay contract thống nhất. |
| IN10 N | `GET /internal/v1/orders/by-quotation/{quotationId}` | Commerce → RFQ | quotationId → OrderCreationResponse | Lookup kết quả khi POST timeout; 404 chưa đủ kết luận POST cũ không còn đang chạy. |
| IN11 S, legacy | `GET /internal/v1/seller-orders/{sellerOrderId}/payment-context` | Commerce hiện có route | sellerOrderId → SellerOrderPaymentContext | TV3/TV5/TV6 dùng Java service; không bắt nội bộ Commerce phải gọi HTTP endpoint này. |
| IN12 C | `POST /internal/v1/inventory/reservations`; `GET /{reservationKey}`; `PUT /{reservationKey}/confirm`, `/release` | Catalog → Commerce | reservationKey + expiresAt + variant quantities → trạng thái hold | Chỉ caller `commerce`; Catalog giữ kho theo key, hết hạn không còn chiếm availability; checkout/payment chưa tích hợp. |

`VariantSnapshotResponse` đích cần variantId, productId, storeId, supplierId, productCode, productName, variantName, sku, imageUrl?, basePrice, availability/status. Retail dùng giá Catalog; negotiated quotation dùng giá đã accept, không đổi trường `agreedUnitPrice` hiện có sang ý nghĩa mới mà chưa migrate consumer.

IN09 payload đích gồm:

- `rfqId`, `quotationId`, `quotationVersion`, `acceptedAt`, `customerId`, `supplierId`, `storeId`: do RFQ service lấy từ trạng thái đã xác thực, không forward nguyên body accept của FE.
- `storeSnapshot {storeName, supplierName}` và `deliveryAddressSnapshot {sourceAddressId, recipientName, recipientPhone, addressLine, ward?, district?, city}`: lấy từ nguồn hợp lệ, xác minh buyer/owner; Commerce lưu bản bất biến.
- `terms {depositPercent, deliveryDate, shippingMethod, shippingFee, paymentTerms?}` cùng amounts đã thống nhất; không để thiếu ngày giao rồi tự suy due.
- `items[] {variantId?, productCodeSnapshot?, productNameSnapshot, variantNameSnapshot?, skuSnapshot?, imageUrlSnapshot?, quantity, unitPrice, discountAmount, subtotal}`: map QUOTATION_ITEM custom name sang order display; Commerce validate cấu trúc/tổng nhưng không reprice Catalog.
- `subtotal`, `totalAmount`: ngữ nghĩa shipping/discount phải được TV3–TV4 chốt. Currency chung, rounding và zero-obligation treatment phải thống nhất TV5.

OrderCreationResponse DTO đã có: `orderId`, `sellerOrderId`, `quotationId`, `quotationVersion`, `status`. Wire version truyền rõ; physical SELLER_ORDER hiện chỉ có quotationId ref, nếu cần persist version riêng phải cập nhật physical hoặc thống nhất dùng immutable quotation ID làm định danh version. Request DTO RFQ/Commerce đã có snapshot nhưng TV4 chưa lấy dữ liệu tin cậy để dựng payload và TV3 chưa persist conversion.

Nhóm đã chọn inventory reservation và Catalog có IN12 cùng migration V4 (`V4__stock_reservation.sql`). **TTL đã chốt 15 phút; còn cần TV5 chốt điểm confirm/release và compensation/recovery giữa các DB**; vì vậy có API giữ kho chưa đồng nghĩa checkout distributed đã hoàn chỉnh. Tham khảo `docs/contracts/README.md` để xem payload/status.

### 11.5. Contract Java trong Commerce

| Provider owner → consumer | Contract | Trách nhiệm |
|---|---|---|
| TV3 → TV5/TV6 | `getSellerOrderContext(sellerOrderId)` | Owner, source, snapshot terms, amount, current state. |
| TV5 → TV3/TV6 | `getPaymentStatus`, `assertEligibleForFulfillment` | FULL/DEPOSIT/REMAINING, HELD và điều kiện giao theo policy. |
| TV6 → TV3/TV5 | `confirmDelivered`, `acceptSellerOrder` | Persist shipment/order state rồi đánh giá settlement theo local transaction boundary. |
| TV5 → TV3/TV6 | `getDisputeBlockingStatus`, `getSettlementStatus` | Chặn payout và phản ánh kết quả finance. |

Đây là contract Java nội bộ cần TV3–TV5–TV6 chốt trước khi bật tạo đơn: TV3 gọi TV5 tạo initial `FULL` obligation cho retail (hoặc `DEPOSIT` cho RFQ) trong cùng Commerce transaction; TV5 gọi Catalog IN12 để confirm/release theo kết quả thanh toán đã xác minh, và có retry bền vững khi gọi mạng lỗi. `PaymentApplicationService` hiện chỉ thực thi `getPaymentContext`; chưa có obligation/callback. Không thêm Feign giữa các phần cùng Commerce và không tự cho checkout thành công khi chưa có các transition này. Đặt trách nhiệm state transition tại một owner rõ ràng để không có ba implementation cập nhật status khác nhau.

## 12. Flow bắt buộc và trường hợp lỗi

### 12.1. Retail

1. TV1/TV2: public store/product → buyer chọn variant; TV3 lưu giỏ theo principal.
2. Preview lấy dữ liệu Catalog; tại checkout kiểm tra lại cart, address owner, store/variant availability, promotion và stock theo strategy đã chốt.
3. Commerce ghi một CUSTOMER_ORDER, một DELIVERY_ADDRESS snapshot, SELLER_ORDER theo store và ORDER_ITEM snapshots. TV5 tạo FULL obligation qua local service theo cùng thiết kế transaction.
4. Commit thành công mới báo tạo đơn; Catalog/Identity không sẵn sàng thì không tạo đơn dùng giá/address giả. Nếu đã reserve stock, rollback local cần release/reconcile reservation.
5. Payment provider callback cập nhật state; TV6 xử lý fulfillment. History lấy snapshot; Catalog đổi tên/giá hoặc bị down không làm mất tên/giá mua cũ.

### 12.2. RFQ accept qua hai DB

1. RQ15 lấy actor từ principal, quotationId từ path và kiểm tra quyền. Nếu là replay đã hoàn tất với cùng payload thì trả kết quả đã lưu; không từ chối chỉ vì quotation đã hết hạn sau lần accept thành công. Với accept mới, kiểm tra RFQ owner, expected version, submitted/expiry và khả năng accept. Không tin customer/supplier/store trong body.
2. Persist quyết định/trạng thái conversion và key bền vững trong RFQ bằng local transaction, theo state machine đã chốt. Khái niệm pending conversion chưa có schema đầy đủ ở V1; cần thiết kế trước khi implement.
3. Gọi IN09 bằng cùng key và immutable snapshot. Commerce dedup quotation/key, tạo order atomically trong DB mình và trả IDs.
4. RFQ xác nhận kết quả bằng transaction riêng. Chỉ phản hồi completed khi biết order đã commit; nếu timeout thì tra IN10 và retry có kiểm soát cùng key.
5. Recovery worker/command phục hồi cả trường hợp Commerce đã commit nhưng RFQ chưa ghi nhận. Không rollback quotation về trạng thái bán được lại chỉ vì client timeout. Không tạo deposit hai lần.
6. Nhiều quotation trên cùng RFQ có thể được accept. Mỗi quotation chuyển thành tối đa một seller order; cách gộp master order, tổng quantity phân bổ và xử lý revisions cùng chain phải chốt, không tự áp một-quotation-mỗi-RFQ.

Đây là yêu cầu phối hợp kiểu Saga qua REST; cơ chế durable state, lookup, retry và compensation phải được code/test. `@Transactional` trên method gọi Feign không đủ; chưa cần tự thêm Kafka để giải quyết.

### 12.3. Finance, shipment, dispute, settlement

1. Supplier chỉ gửi quotation không làm phát sinh deposit. Buyer accept thành công mới tạo DEPOSIT; amount/remaining due từ accepted terms.
2. Webhook verify/dedup rồi ghi kết quả; callback frontend redirect không được coi là chứng cứ payment SUCCESS.
3. Shipment plan/ship/delivered phản ánh expected/actual dates; nhiều shipment cần quy tắc hoàn tất seller order. Buyer acceptance phải persist và retry an toàn.
4. Dispute OPEN chặn settlement. Payout eligibility phải kiểm tra lại tại thời điểm claim xử lý; tránh race giữa open dispute và payout bằng transaction/lock local phù hợp.
5. Settlement snapshot fee 5%; refund/deduction tính đúng một lần. Payout timeout là kết quả chưa rõ, không phát lệnh mới với key khác. Provider xác nhận rồi mới đánh dấu PAID/RELEASE.
6. Buyer/supplier cancel đều qua policy(actor,time,state); không mặc định refund 100%. Số tiền hoàn cộng dồn không vượt số đã thu đủ điều kiện.

### 12.4. State machine chưa được phép tự suy

Spec có các chuỗi trạng thái **gợi ý**; bảng CHECK thực tế và policy phải được đối chiếu trước khi chuyển thành code. Mỗi flow cần bảng `fromState + command + actor + guard → toState + sideEffect`, kèm test cho transition bị cấm.

Các quyết định còn mở: required verification để bán; draft/publish và chỉnh RFQ sau báo giá; revision/accept race; quote allocation/master-order grouping; phân bổ chênh lệch khi ghi `ORDER_ITEM DECIMAL(18,2)`; lifecycle payment/hold; refund matrix; dispute authority; nhiều shipment/return/failure; acceptance timeout; review eligibility; archive/delete; currency và due-time timezone. Đã chốt promotion cộng dồn, BUY_X_GET_Y tốt nhất trên mỗi reward line trước fixed, rồi cộng tỷ lệ percent trên phần còn lại; fixed toàn store phân bổ theo tỷ lệ line subtotal, làm tròn lên 1 VND tại tổng seller order; giữ kho 15 phút. Không đưa enum như `ARCHIVED` hoặc cột `acceptedAt` vào entity nếu DB/contract chưa hỗ trợ.

## 13. Gateway, compatibility và lộ trình áp dụng

| Phần | Hiện tại kiểm tra trong code | Việc áp dụng convention |
|---|---|---|
| Public routing | Gateway đã route `/api/supplier-profile/**` về Identity | Giữ ownership này, không dùng routing sai trong README/TODO cũ. |
| Route mới | Đã có `/api/admin/suppliers/**`; chưa có `/api/seller-orders/**` | Chỉ thêm route Commerce cùng PR implement endpoint. |
| WebSocket | RFQ có `/ws/chat`, Gateway hiện dùng Gateway MVC và chưa có route WS | Kiểm tra khả năng proxy WebSocket của stack đã chọn; triển khai proxy phù hợp và test handshake/STOMP qua Gateway. Không chỉ copy cấu hình của Gateway WebFlux. |
| Identity | Đã có login/JWT/password_hash V2 và profile/address/supplier logic | Cập nhật TODO cũ; hoàn thiện capability, internal auth, password/session decisions. |
| AuthZ | Identity cấp ROLE_ADMIN từ danh sách user ID đã được provision; internal API dùng service token riêng | Tiếp tục test ownership/service identity; không cấu hình trước ID admin chưa tồn tại. |
| DTO wire | IDs numeric, timestamp LocalDateTime, store supplierIdRef | Lên PR chuyển contract có FE/Feign; không trộn string/numeric ID ngẫu nhiên giữa API mới. |
| Error/PATCH/list | Legacy ErrorResponse, PATCH record, store array | Áp Problem Details, presence-aware PATCH, PageResponse với compatibility plan. |
| Store mapping | StoreResponse không có audit timestamps theo STORE V1 | Kiểm tra mapping với SQL Server thật trước khi merge. |
| Product snapshot | Endpoint tồn tại, implementation TODO | Thêm batch variant snapshot và field đủ cho checkout; cập nhật consumer. |
| Accept quotation | Route tồn tại nhưng chưa bind path ID; body là DTO ID nội bộ | Tách AcceptQuotationRequest public và AcceptedQuotationOrderRequest internal; validate path/principal. |
| Finance/fulfillment | Phần lớn còn skeleton | Không đánh dấu Done chỉ vì tên endpoint đã có trong tài liệu. |
| Legacy design paths | TODO đề xuất `/api/orders/{orderId}/seller-orders/{sellerOrderId}/accept` và settlement lookup bằng sellerOrderId ở path | Catalog này chọn FU08 `/api/seller-orders/{sellerOrderId}/accept`, FI18 theo settlementId và FI17 filter sellerOrderId; các route cũ đó chưa có controller, cập nhật consumer design trước code. |

Ưu tiên triển khai: contract/auth/DB → TV1 summaries + store → TV2 catalog/snapshot/promotion → TV3 checkout/snapshot → TV4 accept + recovery → TV5 finance → TV6 shipment/acceptance/settlement integration. Chat có thể song song sau auth/WS routing. TV5 và TV6 cần thống nhất interface trước khi triển khai song song.

## 14. OpenAPI, test và tiêu chí merge

Mỗi operation cần `operationId`, tag/owner, security requirement, path/query/header schema, request required/nullable/readOnly fields, success/error schema, example, pagination, idempotency và rule state/owner. OpenAPI của từng service là contract thực thi; tài liệu này là danh mục và quy ước để xây contract đó. Tham khảo [OpenAPI Specification 3.1.1](https://spec.openapis.org/oas/v3.1.1.html); dùng phiên bản tương thích tooling hiện có, không tự nâng dependency trong PR nghiệp vụ.

Không dùng Swagger UI ở service để FE gọi bỏ qua Gateway. Publish tài liệu/spec phục vụ dev có kiểm soát; không expose Actuator/env hay internal operation ra public chỉ để tiện test.

| Nhóm test | Bằng chứng cần có |
|---|---|
| Contract | Request/response đúng schema; ID/money/time format; missing/null/unknown fields; status và error code ổn định. |
| Authentication/ownership | Thiếu token, token sai/hết hạn, wrong audience/caller; user A truy cập dữ liệu B; supplier không xem phần đơn của supplier khác; admin capability không được tự khai báo. |
| Persistence | SQL Server + migration V1 và các version mới; FK/unique/CHECK, precision, UTC, rollback local. H2 smoke test không thay thế. |
| Retail | Giỏ >=2 store tạo đúng master/seller orders; promotion 3 loại; BUY_X_GET_Y khác reward item; stock cạnh tranh; checkout replay; snapshot sau catalog update. |
| RFQ | Public feed/quotation visibility; revision immutable; nhiều supplier được accept trên RFQ; cùng quotation không ra hai order; timeout trước/sau Commerce commit; recovery sau restart. |
| Money | Deposit chỉ sau accept; remaining due; webhook lặp/sai chữ ký/out-of-order; refund không vượt paid; dispute/payout race; fee 5%; payout timeout/replay. |
| Fulfillment/chat | Shipment owner/state/dates; multiple shipments; acceptance/review eligibility; chat subscribe trái phép; persist trước broadcast; reconnect/history. |
| FE | Loading/empty/error, expired auth, amount từ server, tránh double submit, keyboard/form labels và flow qua Gateway. |

PR checklist:

- [ ] Owner/consumer thống nhất API trong catalog; mục T đã có quyết định bằng văn bản nếu triển khai.
- [ ] Đúng 3 lớp, đúng DB owner; không share entity/repository, không Feign nội bộ Commerce.
- [ ] DTO/OpenAPI/Gateway/Feign/FE cùng khớp; có migration cho persistent state mới.
- [ ] Service enforce quyền/owner, validation và state transition; response không lộ dữ liệu.
- [ ] Idempotency/concurrency/failure path được test cho command có side effect quan trọng.
- [ ] Test liên quan, build và FE lint/build thành công; ghi rõ phần chưa chạy do môi trường.
- [ ] Không claim full chức năng từ danh mục API: chỉ Done khi demo được flow với SQL Server, Gateway và frontend.

## 15. Cách team dùng tài liệu này

1. Mỗi người lấy các mã API của phần mình, chốt mục T cùng người liên quan, rồi viết OpenAPI/DTO trước.
2. Provider gửi consumer schema và ví dụ lỗi; frontend có thể mock theo schema đó trong khi backend triển khai.
3. Code controller → service → repository/migration; transaction và quyền ở service, không ở component React.
4. Chạy happy path + failure/concurrent/retry cases, cập nhật trạng thái task thật. Chỉ thay nhãn C/S/N khi đã kiểm tra code; nhãn C vẫn không thay test.
5. Khi thay contract, cập nhật **tài liệu này và OpenAPI cùng PR**; không để convention, TODO và controller thành ba nguồn API khác nhau.
