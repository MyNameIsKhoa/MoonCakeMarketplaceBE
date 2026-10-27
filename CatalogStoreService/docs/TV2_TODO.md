# CatalogStoreService — Việc cần làm của TV2 (Catalog, Product, Promotion)

> Cập nhật: 2026-10-20 · Người phụ trách: Nguyễn Hữu Đạt (TV2)
> Nhật ký từng ngày: [`docs/NguyenHuuDat.md`](../../docs/NguyenHuuDat.md)

## 1. Phạm vi trong service

`CatalogStoreService` (port 8082, DB `mss_catalog_store`) do hai người cùng làm:

| Phần | Người làm | Bảng | Code chính |
|---|---|---|---|
| Store | TV1 | `store` | `StoreController`, `StoreService(Impl)`, `StoreEntity`, `InternalStoreController` |
| Catalog + Promotion | **TV2** | `category`, `product`, `product_variant`, `promotion`, `promotion_rule` | Các file liệt kê ở mục 3 |

TV2 **không sửa** file `Store*` của TV1. Quyền chủ store đi qua `StoreAccessService` (của TV2). File dùng chung với TV1 cần báo trước khi sửa: `SecurityConfig`, `GlobalExceptionHandler`, `exception/*`, `application.yml`, `db/migration/*`.

**Ai dùng API của TV2:**

- **TV3 (Commerce):** IN07 snapshot theo lô, IN08 tính khuyến mãi, sau này thêm API trừ tồn kho.
- **TV4 (RFQ):** IN06 snapshot một variant.
- **FE:** CA01–CA20 qua Gateway (`/api/categories/**`, `/api/products/**`, `/api/promotions/**` đã có route).

## 2. Quy tắc nghiệp vụ đã thống nhất trong code

| Chủ đề | Quy tắc |
|---|---|
| Quyền supplier | API ghi yêu cầu `ROLE_SUPPLIER`/`ROLE_ADMIN` (SecurityConfig). Role chốt lúc đăng nhập nên service vẫn xác minh `userId` → Identity supplier summary → so `store.supplierIdRef`; supplier phải `VERIFIED` tại thời điểm ghi |
| Store bị khóa | Store `SUSPENDED` thì không sửa catalog/promotion được |
| Hiển thị public | Chỉ product `ACTIVE` thuộc store `ACTIVE`; variant `ACTIVE`; promotion `ACTIVE`. Dữ liệu chưa công bố trả 404 cho người ngoài, owner đăng nhập vẫn xem được |
| Trạng thái ban đầu | Product, variant, promotion mới đều là `DRAFT`; category mới là `ACTIVE` |
| Product → ACTIVE | Chỉ khi có ít nhất 1 variant `ACTIVE` |
| productCode | Server sinh `PRD-` + 8 ký tự hex viết hoa, unique trong store; client không gửi |
| Giá | Nằm ở variant (`basePrice`), không có ở product |
| Không reparent | Không đổi `storeId` của product/promotion, không đổi `productId` của variant |
| Category | Chỉ `ROLE_ADMIN` được ghi; không cho parent là chính nó hoặc con cháu của nó |
| Promotion rule | `PERCENT_DISCOUNT` cần `discountPercent`; `FIXED_DISCOUNT` cần `discountAmount`; `BUY_X_GET_Y` cần `targetVariantId`, `rewardVariantId`, `minQuantity`, `rewardQuantity`. Target/reward phải cùng store với promotion |
| Phân trang | `PageResponse<T>`, `page` từ 0, `size` mặc định 20, tối đa 100; vượt giới hạn trả 400 |
| Snapshot IN06 | Giữ nguyên `ProductSnapshotResponse` (`agreedUnitPrice` = `basePrice`) vì Commerce/RFQ đã mirror |

## 3. Trạng thái hiện tại

**Đã xong (2026-10-20):**

- [x] Entity có getter/setter; `CategoryStatus`, `PromotionType` dạng enum
- [x] Repository query cho 5 bảng; search product bằng Specification (static method trong `ProductRepository`)
- [x] `SecurityConfig`: GET catalog public, ghi category chỉ admin
- [x] `StoreAccessService(Impl)`: kiểm tra chủ store
- [x] Service interface: `CategoryService`, `ProductService`, `ProductVariantService`, `PromotionService`, `PromotionCalculationService`, `CatalogApplicationService` (thêm IN07)
- [x] Request/response DTO cho toàn bộ API của TV2
- [x] Test `ProductRepositorySearchTest` (5 test, H2)

- [x] Category CA01–CA04 + seed V3 (chi tiết ở Bước 2)
- [x] Product CA06–CA09 (chi tiết ở Bước 3)
- [x] Variant CA11–CA14 (chi tiết ở Bước 4)
- [x] Snapshot IN06, IN07 + contract (chi tiết ở Bước 5)
- [x] Promotion CA16–CA20 (chi tiết ở Bước 6)
- [x] Promotion engine IN08 (chi tiết ở Bước 7)

**Đã bổ sung khi tích hợp TV3:** IN12 reserve/get/confirm/release và migration V4 (`V4__stock_reservation.sql`, đổi từ V2 vì trùng version); chưa được checkout/payment gọi. FE chưa nối API thật.

### 3.1. Danh sách API TV2 đã làm (20 endpoint)

Đối chiếu trực tiếp với mapping trong `controller/`. Quyền: **Public** = không cần JWT; **Owner** = JWT + supplier `VERIFIED` sở hữu store (xác minh qua Identity); **Admin** = `ROLE_ADMIN`; **Service** = header `X-Service-Name` + `X-Service-Token`.

**Public API qua Gateway** (17)

| Mã | Method + path | Quyền | Thành công | Controller |
|---|---|---|---|---|
| CA01 | `GET /api/categories?parentCategoryId=&page=&size=&sort=` | Public | 200 `PageResponse<CategoryResponse>` | `CategoryController` |
| CA02 | `GET /api/categories/{categoryId}` | Public | 200 `CategoryResponse` | `CategoryController` |
| CA03 | `POST /api/categories` | Admin | 201 + `Location` | `CategoryController` |
| CA04 | `PATCH /api/categories/{categoryId}` | Admin | 200 | `CategoryController` |
| CA06 | `GET /api/products?q=&storeId=&categoryId=&mine=&page=&size=&sort=` | Public; `mine=true` cần JWT | 200 `PageResponse<ProductSummaryResponse>` | `ProductController` |
| CA07 | `GET /api/products/{productId}` | Public; owner thấy bản nháp | 200 `ProductResponse` (kèm variants) | `ProductController` |
| CA08 | `POST /api/products` | Owner | 201 + `Location` | `ProductController` |
| CA09 | `PATCH /api/products/{productId}` | Owner | 200 | `ProductController` |
| CA11 | `GET /api/products/{productId}/variants?page=&size=&sort=` | Public; owner thấy mọi trạng thái | 200 `PageResponse<VariantResponse>` | `ProductVariantController` |
| CA12 | `GET /api/products/{productId}/variants/{variantId}` | Public; owner thấy mọi trạng thái | 200 `VariantResponse` | `ProductVariantController` |
| CA13 | `POST /api/products/{productId}/variants` | Owner | 201 + `Location` | `ProductVariantController` |
| CA14 | `PATCH /api/products/{productId}/variants/{variantId}` | Owner | 200 | `ProductVariantController` |
| CA16 | `GET /api/promotions?storeId=&mine=&page=&size=&sort=` | Public; `mine=true` cần JWT | 200 `PageResponse<PromotionResponse>` | `PromotionController` |
| CA17 | `GET /api/promotions/{promotionId}` | Public; owner thấy bản nháp | 200 `PromotionResponse` (kèm rules) | `PromotionController` |
| CA18 | `POST /api/promotions` | Owner | 201 + `Location` | `PromotionController` |
| CA19 | `PATCH /api/promotions/{promotionId}` | Owner | 200 | `PromotionController` |
| CA20 | `PUT /api/promotions/{promotionId}/rules` | Owner | 200 | `PromotionController` |

**Internal API cho Commerce/RFQ, không qua Gateway** (3) — contract ở [`docs/contracts/README.md`](../../docs/contracts/README.md)

| Mã | Method + path | Caller | Thành công | Controller |
|---|---|---|---|---|
| IN06 | `GET /internal/v1/products/{productId}/variants/{variantId}/snapshot` | Service (`commerce`, `rfq`) | 200 `ProductSnapshotResponse` | `InternalCatalogController` |
| IN07 | `POST /internal/v1/variants/snapshots` | Service (`commerce`, `rfq`) | 200 `List<VariantSnapshotResponse>` | `InternalCatalogController` |
| IN08 | `POST /internal/v1/promotions/calculate` | Service (`commerce`) | 200 `PromotionCalculationResult` | `InternalCatalogController` |

**API trong spec nhưng chưa làm** (đều đánh dấu cần CHỐT trong spec hoặc chờ nhóm khác)

| Mã | Method + path | Lý do chưa làm |
|---|---|---|
| CA05 | `DELETE /api/categories/{categoryId}` | Chưa chốt chính sách xóa/lưu trữ; tạm dùng `PATCH status=INACTIVE` |
| CA10 | `DELETE /api/products/{productId}` | Chưa chốt chính sách xóa; tạm dùng `PATCH status=INACTIVE` |
| CA15 | `DELETE /api/products/{productId}/variants/{variantId}` | Chưa chốt; variant đang được promotion rule tham chiếu (FK local) |
| CA21 | `DELETE /api/promotions/{promotionId}` | Chưa chốt; tạm dùng `PATCH status=DISABLED` |
| AD06 | `POST /api/products/{productId}/moderation` | Chưa chốt quy trình kiểm duyệt sản phẩm của admin |
| — | API internal trừ tồn kho | Chờ TV3 chốt luồng checkout (Bước 8) |

### 3.2. Chạy thử trên SQL Server local qua Gateway

Đã chạy ngày 2026-10-20: **53/53 kiểm tra pass** (`scripts/catalog-smoke.ps1`).

```powershell
# 1. Một lần: tạo .env (secret ngẫu nhiên + MOONCAKE_DB_USER/PASSWORD), rồi tạo DB + login
.\scripts\local-sqlserver-setup.ps1
# 2. Build và chạy 3 service từ thư mục root backend (đọc .env ở root)
.\mvnw.cmd -pl IdentityService,CatalogStoreService,API-Gateway clean package
java -jar IdentityService\target\identity-service-0.0.1-SNAPSHOT.jar      # 8081
java -jar CatalogStoreService\target\catalog-store-service-0.0.1-SNAPSHOT.jar  # 8082
java -jar API-Gateway\target\api-gateway-0.0.1-SNAPSHOT.jar               # 8080
# 3. Lần đầu: tạo admin, lấy userId, khởi động lại Identity với $env:ADMIN_USER_IDS=<userId>
.\scripts\catalog-smoke.ps1 -RegisterAdmin -AdminEmail <email> -AdminPassword <password>
# 4. Chạy smoke test
.\scripts\catalog-smoke.ps1 -AdminEmail <email> -AdminPassword <password>
```

- [x] Migration V1–V3 chạy trên SQL Server (sau khi thêm `spring-boot-starter-flyway`)
- [x] Gateway mở public GET catalog
- [x] Non-admin ghi category trả 403 (sửa lỗi forward `/error`)
- [ ] Search không dấu chưa hỗ trợ (collation `Vietnamese_CI_AS` phân biệt dấu) — **CHỐT** với nhóm

## 4. Checklist theo thứ tự làm

Thứ tự theo phụ thuộc dữ liệu: `product.category_id` bắt buộc → làm Category trước; Variant cần Product; Promotion cần Variant.

### Bước 2 — Category ✅ xong 2026-10-20

- [x] `db/migration/V3__seed_categories.sql`: seed 5 danh mục gốc `truyen-thong`, `hien-dai`, `chay`, `cao-cap`, `thieu-nhi` (FE đang dùng `hiện-đại` có dấu, cần đổi sang `hien-dai`)
- [x] `CategoryServiceImpl`
  - [x] CA01 `GET /api/categories?parentCategoryId=&page=&size=&sort=`: danh mục gốc hoặc con trực tiếp, chỉ `ACTIVE`; sort allowlist `name`, `slug`, `id`
  - [x] CA02 `GET /api/categories/{categoryId}`: không tồn tại hoặc `INACTIVE` trả 404
  - [x] CA03 `POST /api/categories`: slug trùng 409 (cả trường hợp hai request đồng thời), parent phải `ACTIVE`; trả 201 + `Location`
  - [x] CA04 `PATCH /api/categories/{categoryId}`: chặn chu trình parent, slug trùng 409, status sai 400
- [x] `CategoryController`
- [x] `dto/request/PageQuery`: record `?page=&size=&sort=` kiểm tra phân trang, dùng chung cho mọi listing
- [x] Tách route internal: `ProductController` cũ → `InternalCatalogController` (`/internal/v1/...`); route IN06 giữ nguyên
- [x] Test: `CategoryServiceImplTest` (14), `CategoryControllerTest` (9: public GET, size/sort sai 400, 401, 403 non-admin, 201 + Location)
- [ ] Chạy thử migration V3 trên SQL Server thật (test H2 tắt Flyway nên chưa kiểm tra)
- [ ] Chưa hỗ trợ: đưa danh mục con về gốc qua PATCH (record chưa phân biệt field vắng/null); CA05 xóa danh mục

### Bước 3 — Product ✅ xong 2026-10-20

- [x] `ProductServiceImpl`
  - [x] CA06 `GET /api/products?q=&storeId=&categoryId=&mine=&page=&size=&sort=`: dùng `ProductRepository.publicSearch` / `ownerSearch`; `mine=true` không đăng nhập thì 401; sort allowlist (`name`, `createdAt`, `id`), mặc định `createdAt,desc`; sort lạ trả 400
  - [x] `ProductSummaryResponse.minPrice/imageUrl` lấy từ variant `ACTIVE`, load theo lô bằng `findByProductIdInOrderByIdAsc` (tránh N+1)
  - [x] CA07 `GET /api/products/{productId}`: kèm variants; người ngoài chỉ thấy product `ACTIVE` của store `ACTIVE` và variant `ACTIVE`; owner thấy tất cả
  - [x] CA08 `POST /api/products`: `requireOwnedStore`, category `ACTIVE`, sinh `PRD-XXXXXXXX`, status `DRAFT`; trả 201 + `Location`
  - [x] CA09 `PATCH /api/products/{productId}`: owner; transition `DRAFT → ACTIVE/INACTIVE`, `ACTIVE ↔ INACTIVE`; không quay lại `DRAFT`; không đặt tay `OUT_OF_STOCK`; lên `ACTIVE` cần variant `ACTIVE`
- [x] `ProductController` mới cho `/api/products`; mapping entity → DTO là method private trong từng `*ServiceImpl`
- [x] Test: `ProductServiceImplTest` (14), `ProductControllerTest` (9)
- [x] Unit test `StoreAccessServiceImplTest` (10): supplier chưa `VERIFIED` → 400, store `SUSPENDED` → 403, không phải owner → 403, Identity 404 → không phải supplier, Identity 500 → lỗi hệ thống (2026-10-22)

### Bước 4 — Variant ✅ xong 2026-10-20

- [x] `ProductVariantServiceImpl`
  - [x] CA11 `GET /api/products/{productId}/variants`: owner thấy mọi trạng thái; public chỉ variant `ACTIVE` của product đã công bố
  - [x] CA12 `GET /api/products/{productId}/variants/{variantId}`: dùng `findByIdAndProductId`, sai product trả 404
  - [x] CA13 `POST /api/products/{productId}/variants`: SKU trùng 409 (kể cả request đồng thời), status `DRAFT`, 201 + `Location`
  - [x] CA14 `PATCH /api/products/{productId}/variants/{variantId}`: SKU mới trùng 409; transition giống product; **chọn chặn (400)** khi tắt variant `ACTIVE` cuối cùng của product đang `ACTIVE`
- [x] Tách `ProductVariantController` (`/api/products/{productId}/variants`), sort allowlist `basePrice`, `variantName`, `id`
- [x] Test: `ProductVariantServiceImplTest` (12), `ProductVariantControllerTest` (5)
- [ ] Tồn kho hiện đặt giá trị tuyệt đối qua PATCH (không có optimistic lock vì V1 không có cột version). Trừ kho khi checkout đi đường riêng (Bước 8)

### Bước 5 — Snapshot cho TV3/TV4 ✅ xong 2026-10-20

- [x] IN06 `CatalogApplicationServiceImpl.getProductSnapshot`: kiểm tra variant thuộc product, 404 nếu sai; giữ field `agreedUnitPrice`
- [x] IN07 `POST /internal/v1/variants/snapshots`: giữ thứ tự đầu vào, gộp ID trùng, thiếu ID trả 404 kèm danh sách ID thiếu; load variant + product + store bằng 3 query cho cả batch
- [x] Cập nhật `docs/contracts/README.md`: IN06 (ý nghĩa `agreedUnitPrice`, 404), IN07 (request, response, thứ tự, lỗi), quyền caller
- [x] Contract test `InternalCatalogContractTest` (6, full context + H2 + filter internal thật): field JSON, thứ tự/gộp, 404, 400 rỗng, 401 thiếu/sai token
- [ ] **Báo TV3/TV4 contract đã sẵn sàng** (gửi link `docs/contracts/README.md`)

### Bước 6 — Promotion CRUD ✅ xong 2026-10-20

- [x] `PromotionServiceImpl`
  - [x] CA16 `GET /api/promotions?storeId=&mine=&page=&size=&sort=`: public chỉ promotion `ACTIVE`, chưa kết thúc, thuộc store `ACTIVE` (`PromotionRepository.publicListing`); `mine=true` thấy mọi trạng thái; rules load theo lô
  - [x] CA17 `GET /api/promotions/{promotionId}`: kèm rules; chưa công bố/đã kết thúc → 404 cho người ngoài
  - [x] CA18 `POST /api/promotions`: owner, `startAt < endAt`, 1–20 rule, kiểm tra field theo `ruleType` (field không dùng phải null), `countInStore` khớp số variant; tạo `DRAFT`
  - [x] CA19 `PATCH /api/promotions/{promotionId}`: transition `DRAFT → ACTIVE/DISABLED`, `ACTIVE ↔ DISABLED`; không về `DRAFT`, không đặt tay `EXPIRED`; không kích hoạt promotion đã kết thúc
  - [x] CA20 `PUT /api/promotions/{promotionId}/rules`: validate trước, rồi `deleteByPromotionId` + insert mới trong cùng `@Transactional`
- [x] `PromotionController`; body mảng của CA20 được validate từng phần tử bằng `Validator`
- [x] `ClockConfig` (bean `Clock` UTC) để test cố định thời điểm; engine IN08 dùng lại
- [x] Test: `PromotionServiceImplTest` (16), `PromotionControllerTest` (6), `PromotionRepositoryListingTest` (2, H2)
- [ ] Trạng thái `EXPIRED` hiện chỉ suy ra khi đọc (`endAt <= now`), chưa có job cập nhật cột `status`

### Bước 7 — Promotion engine IN08 ✅ xong 2026-10-20

- [x] Phần tính thuần `PromotionCalculationServiceImpl.computeDiscounts(...)` (static, không truy cập DB) để unit test trực tiếp — gộp từ `PromotionCalculator` ngày 22/10 theo quy ước package
- [x] `PromotionCalculationServiceImpl`: load variant + promotion hiệu lực (`findEffective`) + rules, gọi calculator
- [x] `POST /internal/v1/promotions/calculate` trong `InternalCatalogController`
- [x] Quy tắc nhóm đã chốt ngày 21/10/2026: một `BUY_X_GET_Y` có lợi nhất trên reward line, sau đó cộng dồn fixed theo tỷ lệ line subtotal (phần lẻ vào line cuối), cuối cùng cộng các tỷ lệ percent trên phần còn lại; tổng seller order làm tròn lên VND. Không dùng quy tắc TV2 cũ “1 rule tốt nhất/line” hoặc `HALF_UP` theo line.
- [ ] Đối soát rule `minQuantity`, trường hợp trigger = reward và line decimal với TV2/TV3; chạy lại contract test sau merge.
- [x] Ghi contract IN08 vào `docs/contracts/README.md`, có Feign mirror ở Commerce

### Bước 8 — Chờ TV3 (chưa code)

- [x] Đã có IN12 reserve/get/confirm/release, migration V4 trong Catalog và Feign mirror Commerce; stock_quantity là on-hand, hold còn hiệu lực được trừ khỏi availability. TTL đã chốt 15 phút; checkout/payment chưa dùng contract này, mốc confirm/release vẫn cần TV5 chốt.
- [x] **TV2 (2026-10-22):** IN07 thêm `availableQuantity` (= tồn thực tế − hold `HELD` còn hạn, tính theo lô 1 query); CA14 khóa dòng variant rồi chặn đặt `stockQuantity` thấp hơn lượng đang giữ (409). Smoke test bước 8 kiểm tra trên SQL Server.

### Bước 9 — Frontend (`MoonCakeMarketplaceFE`)

- [x] **Nền tảng (2026-10-22):** `src/services/api/http.ts` (axios → Gateway `VITE_API_BASE_URL`, mặc định `http://localhost:8080`; gắn `Bearer` token; `ApiError` với `status`/`fieldErrors`; `setAccessToken` cho màn đăng nhập của TV1), `catalogTypes.ts` (type khớp DTO backend), `catalogApi.ts` (CA01–CA04, CA06–CA09, CA11–CA14, CA16–CA20), export qua `services/api/index.ts`. `npm run lint` + `npm run build` pass; đã gọi thật qua Gateway (9/9)
- [x] CORS: Gateway mặc định cho phép `http://localhost:3000` (FE `npm run dev`) và `:5173`
- [ ] Thay `src/services/api/mockBackend.ts` bằng API thật cho `ProductList`, `ProductDetail`, `BrandStore`
- [ ] `pages/seller/SellerCenter.tsx`: quản lý product/variant/promotion
- [ ] Bỏ phần tự tính khuyến mãi ở FE; giá cuối do server tính

## 5. Điểm còn phải CHỐT

| # | Câu hỏi | Đề xuất hiện tại | Chốt với |
|---|---|---|---|
| 1 | Nhiều promotion cùng áp dụng | Đã chốt cộng dồn; một BUY_X_GET_Y tốt nhất trước fixed, sau đó cộng tỷ lệ percent | TV3, nhóm |
| 2 | Làm tròn tiền | Đã chốt lên 1 VND tại tổng cuối từng seller order; phân bổ chênh lệch xuống line còn TODO | TV3, TV5 |
| 3 | `FIXED_DISCOUNT` khi `targetVariantId` null | Đã chốt giảm trên tổng store, phân bổ theo tỷ lệ line subtotal, phần lẻ vào line cuối | TV3 |
| 4 | Trừ tồn kho / chống bán quá | IN12 giữ 15 phút; chốt payment transition và đảm bảo mọi stock update kiểm tra hold | TV3, TV5 |
| 5 | Xóa product/variant/promotion (CA10, CA15, CA21) | Chưa làm; dùng `INACTIVE`/`DISABLED` thay vì xóa | Nhóm |
| 6 | Search không dấu trên SQL Server | **Đã kiểm tra:** collation `Vietnamese_CI_AS` phân biệt dấu → "banh nuong" không ra "Bánh nướng". Lựa chọn: collation `_AI` cho cột `name` (migration V4) hoặc cột tên chuẩn hóa | Nhóm |
| 7 | Tắt variant `ACTIVE` cuối cùng của product đang `ACTIVE` | **Đã chọn: chặn (400)**, đã code ở CA14 | Tự quyết, đã ghi lại |

## 6. Definition of Done cho mỗi bước

- Logic nằm ở `service/impl`; controller chỉ nhận/validate request, lấy `userId` từ `@AuthenticationPrincipal UserPrincipal`, không nhận `supplierId`/`userId` từ body
- Không trả entity ra REST; dùng DTO record
- Có unit test cho rule quyền/trạng thái/tiền và controller test cho request–response
- `mvnw.cmd -pl CatalogStoreService test` pass toàn bộ (kể cả test của TV1)
- Cập nhật `docs/NguyenHuuDat.md` và tick checklist trong file này
