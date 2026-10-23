# Nhật ký làm việc — Nguyễn Hữu Đạt (TV2)

**Vai trò:** TV2 — Catalog, Product, Promotion
**Service:** `CatalogStoreService` (port 8082, DB `mss_catalog_store`), dùng chung với TV1 (phần Store)
**Bảng sở hữu:** `category`, `product`, `product_variant`, `promotion`, `promotion_rule`

Checklist chi tiết những việc còn lại của TV2: [`CatalogStoreService/docs/TV2_TODO.md`](../CatalogStoreService/docs/TV2_TODO.md).

---

## 2026-10-20

### Tóm tắt

- Rà soát cách giao task trong `TODO.md` / `TODO_IMPLEMENTATION.md`, đối chiếu với code thực tế và phân tích phạm vi TV2.
- Hoàn thành **Bước 1 — nền tảng**: entity có getter/setter, enum hóa status, repository query, mở public GET catalog trong `SecurityConfig`, service kiểm tra chủ store.
- Hoàn thành **định nghĩa service interface + DTO** cho toàn bộ API của TV2 (CA01–CA04, CA06–CA09, CA11–CA14, CA16–CA20, IN06–IN08).
- Chuyển search product (CA06) từ `@Query` sang **JPA Specification**, kèm test trên H2.
- `mvnw.cmd -pl CatalogStoreService test`: **37/37 test pass** (32 test cũ của TV1 + 5 test mới).

### File thêm mới

Đường dẫn tính từ `CatalogStoreService/src/main/java/mss/mooncake/catalogstore/`.

| Nhóm | File | Mục đích |
|---|---|---|
| Enum | `enums/CategoryStatus.java` | `ACTIVE`, `INACTIVE` cho category (DB không có CHECK nên enum là nơi giới hạn giá trị) |
| Service | `service/StoreAccessService.java`, `service/impl/StoreAccessServiceImpl.java` | Kiểm tra supplier VERIFIED + chủ store qua Identity Feign; lấy danh sách store của supplier cho `mine=true`. Tách riêng để không sửa file của TV1 |
| Service interface | `service/CategoryService.java` | CA01–CA04 |
| | `service/ProductService.java` | CA06–CA09 |
| | `service/ProductVariantService.java` | CA11–CA14 |
| | `service/PromotionService.java` | CA16–CA20 |
| | `service/PromotionCalculationService.java` | IN08 — promotion engine cho Commerce |
| Request DTO | `dto/request/CreateCategoryRequest.java`, `UpdateCategoryRequest.java` | Category |
| | `dto/request/CreateProductRequest.java`, `UpdateProductRequest.java` | Product (không có giá; productCode do server sinh) |
| | `dto/request/CreateVariantRequest.java`, `UpdateVariantRequest.java` | Variant (giá, tồn kho, SKU) |
| | `dto/request/CreatePromotionRequest.java`, `UpdatePromotionRequest.java`, `PromotionRuleRequest.java` | Promotion + rule |
| | `dto/request/VariantSnapshotBatchRequest.java` | IN07, tối đa 100 variant |
| | `dto/request/PromotionCalculationRequest.java` | IN08, chỉ nhận variantId + quantity |
| Response DTO | `dto/response/PageResponse.java` | Phân trang chuẩn MSS (`items, page, size, totalElements, totalPages`) |
| | `dto/response/CategoryResponse.java`, `ProductSummaryResponse.java`, `ProductResponse.java`, `VariantResponse.java` | Catalog |
| | `dto/response/PromotionResponse.java`, `PromotionRuleResponse.java` | Promotion |
| | `dto/response/VariantSnapshotResponse.java` | IN07 — snapshot đầy đủ cho Commerce |
| Repository | `repository/ProductSpecifications.java` | Điều kiện search CA06: `publicSearch`, `ownerSearch` |
| Test | `src/test/java/.../repository/ProductSpecificationsTest.java` | 5 test search trên H2 |

### File thay đổi

| File | Thay đổi |
|---|---|
| `entity/CategoryEntity.java` | Thêm getter/setter; `status` đổi từ `String` sang `CategoryStatus` |
| `entity/ProductEntity.java`, `ProductVariantEntity.java`, `PromotionEntity.java` | Thêm getter/setter (viết tay theo style `StoreEntity`) |
| `entity/PromotionRuleEntity.java` | Thêm getter/setter; `ruleType` đổi từ `String` sang `PromotionType` |
| `repository/CategoryRepository.java` | Query danh mục gốc/con theo status, kiểm tra slug, kiểm tra có danh mục con |
| `repository/ProductRepository.java` | `extends JpaSpecificationExecutor`; `existsByStoreIdAndProductCode`, `existsByCategoryId` |
| `repository/ProductVariantRepository.java` | Query theo product, kiểm tra variant thuộc product, SKU unique, `countInStore` (kiểm tra variant cùng store cho promotion rule) |
| `repository/PromotionRepository.java` | Query theo store/status, `findEffective` (promotion đang hiệu lực tại thời điểm) |
| `repository/PromotionRuleRepository.java` | Query theo promotion, `deleteByPromotionId` (bulk delete cho CA20) |
| `security/SecurityConfig.java` | GET `/api/categories/**`, `/api/products/**`, `/api/promotions/**` không cần đăng nhập; ghi category chỉ `ROLE_ADMIN` |
| `service/CatalogApplicationService.java` | Thêm `getVariantSnapshots` (IN07) |
| `service/impl/CatalogApplicationServiceImpl.java` | Thêm stub IN07 (vẫn `UnsupportedOperationException`, implement ở bước sau) |
| `dto/response/PromotionCalculationResult.java` | Thiết kế lại: kết quả theo từng line + danh sách promotion đã áp dụng + reward của BUY_X_GET_Y |

### File xóa

| File | Lý do |
|---|---|
| `dto/request/ProductRequest.java` | Sai mô hình (đặt `basePrice` ở product, thiếu `categoryId`); không được dùng ở đâu; thay bằng `CreateProductRequest` theo convention |

### Quyết định kỹ thuật

1. **Không đổi `ProductSnapshotResponse` (IN06)**, giữ field `agreedUnitPrice`, vì Commerce và RFQ đã mirror DTO này và convention cấm đổi nghĩa field khi consumer chưa migrate. Dùng DTO mới `VariantSnapshotResponse` cho IN07.
2. **Quyền supplier: role + xác minh qua Identity.** JWT có `ROLE_CUSTOMER`, thêm `ROLE_SUPPLIER` khi supplier profile `VERIFIED` lúc đăng nhập (+ `ROLE_ADMIN` nếu nằm trong allowlist). Catalog chặn API ghi khi thiếu `ROLE_SUPPLIER`/`ROLE_ADMIN`, nhưng vẫn kiểm tra `VERIFIED` + ownership qua Identity mỗi lần ghi vì role chỉ cập nhật ở lần đăng nhập sau.
3. **Product, variant, promotion mới đều ở trạng thái `DRAFT`.** Product chỉ được chuyển sang `ACTIVE` khi có ít nhất 1 variant `ACTIVE`.
4. **Search dùng Specification** (Spring Data JPA 4.1: `Specification.allOf(...)`, không dùng `where(null)` cũ). Ký tự `%`/`_` trong từ khóa được escape; danh sách store rỗng trả kết quả rỗng thay vì lỗi `IN ()`.
5. **Quy tắc promotion sau khi tích hợp TV3 (21/10/2026):** một `BUY_X_GET_Y` tốt nhất trên reward line; cộng dồn fixed rồi percent, tổng giảm không vượt subtotal; làm tròn lên VND ở tổng seller order. Quy tắc cũ “một rule/line, `HALF_UP`” đã được thay thế.

### Việc cần trao đổi

- **TV1:** TV2 dùng `StoreAccessService` riêng thay vì sửa `StoreService`. Cả hai cùng sửa `SecurityConfig`, nên báo trước khi đổi file này.
- **TV3:** review DTO `VariantSnapshotResponse` và `PromotionCalculationRequest/Result` khi bắt đầu Commerce; chốt cách trừ tồn kho khi checkout.
- **SQL Server:** search có phân biệt dấu hay không phụ thuộc collation; cần kiểm tra lại khi chạy DB thật.

### Category API (CA01–CA04)

**Kết quả:** `mvnw.cmd -pl CatalogStoreService test` — **60/60 pass** (thêm 23 test).

| Loại | File | Nội dung |
|---|---|---|
| Thêm | `src/main/resources/db/migration/V3__seed_categories.sql` | Seed 5 danh mục gốc: `truyen-thong`, `hien-dai`, `chay`, `cao-cap`, `thieu-nhi` |
| Thêm | `util/PageRequestFactory.java` (ban đầu đặt ở `controller/`, đã chuyển sang `util/` ở Buổi 7) | Dựng `Pageable` theo quy ước MSS: size 1..100, sort allowlist, luôn thêm `id` làm tie-breaker; sai trả 400 |
| Thêm | `service/impl/CategoryServiceImpl.java` | CA01–CA04: chỉ hiển thị `ACTIVE`, chặn chu trình parent, slug trùng 409 (kể cả request đồng thời nhờ `saveAndFlush` + bắt lỗi unique) |
| Thêm | `controller/CategoryController.java` | `/api/categories`; POST trả 201 + `Location` |
| Thêm | `controller/InternalCatalogController.java` | Nhận route IN06 `/internal/v1/products/{productId}/variants/{variantId}/snapshot` (đường dẫn không đổi) |
| Xóa | `controller/ProductController.java` | Trước đây map `/internal/v1/products`; tách ra để `ProductController` mới dành cho `/api/products` |
| Thêm | `src/test/.../service/CategoryServiceImplTest.java` | 14 test: slug trùng, parent tự trỏ/là cháu, status sai, tên rỗng, 404 |
| Thêm | `src/test/.../controller/CategoryControllerTest.java` | 9 test: GET public, size/sort sai 400, 401 không JWT, 403 non-admin, 201 + Location |

**Ghi chú:**

- `GlobalExceptionHandler` (dùng chung với TV1) chưa xử lý lỗi validate `@RequestParam` và JSON sai định dạng (rơi vào 500). TV2 tự kiểm tra `page/size/sort` trong `PageRequestFactory`; cần trao đổi với TV1 để thêm handler cho `HttpMessageNotReadableException`, `MethodArgumentTypeMismatchException`.
- Migration V3 chưa chạy trên SQL Server thật (test H2 tắt Flyway).
- FE đang dùng category `hiện-đại` (có dấu) → cần đổi sang `hien-dai` khi nối API.

### Product API (CA06–CA09)

**Kết quả:** `mvnw.cmd -pl CatalogStoreService test` — **83/83 pass** (thêm 23 test).

| Loại | File | Nội dung |
|---|---|---|
| Thêm | `service/impl/CatalogMapper.java` | Map product/variant → DTO; `minPrice`/`imageUrl` của list chỉ tính trên variant `ACTIVE`. Variant service sẽ dùng lại |
| Thêm | `service/impl/ProductServiceImpl.java` | CA06–CA09: search bằng Specification + load variant theo lô; ẩn product chưa công bố (404); sinh `PRD-XXXXXXXX`; transition status; lên `ACTIVE` cần variant `ACTIVE` |
| Thêm | `controller/ProductController.java` | `/api/products` (public); `mine=true` không JWT trả 401; POST 201 + `Location` |
| Thêm | `src/test/.../service/ProductServiceImplTest.java` | 14 test: tạo DRAFT, 403 non-owner, category `INACTIVE` 400, transition sai 400, ẩn DRAFT/store chưa công bố, variant hiển thị theo quyền, summary |
| Thêm | `src/test/.../controller/ProductControllerTest.java` | 9 test: search public, `mine` 401/200, sort sai 400, 404, 201 + Location, 401, 400 validation, 403 |

**Quy tắc status product đã chọn:** `DRAFT → ACTIVE/INACTIVE`, `ACTIVE ↔ INACTIVE`, không quay về `DRAFT`, không đặt tay `OUT_OF_STOCK` (giống quy tắc store của TV1).

### Variant API (CA11–CA14) + Snapshot IN06/IN07

**Kết quả:** `mvnw.cmd -pl CatalogStoreService test` — **106/106 pass** (thêm 23 test).

| Loại | File | Nội dung |
|---|---|---|
| Thêm | `service/impl/ProductVariantServiceImpl.java` | CA11–CA14: hiển thị theo quyền, SKU unique (409, kể cả đồng thời), transition giống product, chặn tắt variant `ACTIVE` cuối cùng của product đang bán |
| Thêm | `controller/ProductVariantController.java` | `/api/products/{productId}/variants`; POST 201 + `Location` |
| Sửa | `repository/ProductVariantRepository.java` | Thêm `existsByProductIdAndStatusAndIdNot` |
| Sửa | `service/impl/CatalogApplicationServiceImpl.java` | Implement IN06 (bỏ stub `UnsupportedOperationException`) và IN07 (giữ thứ tự, gộp ID trùng, 404 liệt kê ID thiếu, 3 query cho cả batch) |
| Sửa | `controller/InternalCatalogController.java` | Thêm `POST /internal/v1/variants/snapshots` (IN07) |
| Sửa | `docs/contracts/README.md` | Mô tả IN06, IN07 và quyền caller cho TV3/TV4 |
| Thêm | `src/test/.../service/ProductVariantServiceImplTest.java` | 12 test |
| Thêm | `src/test/.../controller/ProductVariantControllerTest.java` | 5 test |
| Thêm | `src/test/.../controller/InternalCatalogContractTest.java` | 6 contract test chạy full context + H2 + filter internal thật: tên field JSON, thứ tự, 404, 400, 401 |

**Mốc:** phần TV3/TV4 cần từ Catalog (IN06, IN07) đã sẵn sàng → cần báo TV3/TV4.

### Promotion API (CA16–CA20)

**Kết quả:** `mvnw.cmd -pl CatalogStoreService test` — **130/130 pass** (thêm 24 test).

| Loại | File | Nội dung |
|---|---|---|
| Thêm | `config/ClockConfig.java` | Bean `Clock` UTC cho rule phụ thuộc thời gian; test dùng `Clock.fixed` |
| Thêm | `repository/PromotionSpecifications.java` | `publicListing` (ACTIVE, chưa kết thúc, store ACTIVE), `ownerListing` |
| Sửa | `repository/PromotionRepository.java` | `extends JpaSpecificationExecutor`; bỏ 3 query listing cũ không còn dùng; giữ `findEffective` cho IN08 |
| Thêm | `service/impl/PromotionServiceImpl.java` | CA16–CA20: kiểm tra field theo `ruleType`, variant cùng store, thời gian, transition; thay rules trong một transaction |
| Thêm | `controller/PromotionController.java` | `/api/promotions`; POST 201 + `Location`; PUT `/rules` validate từng phần tử mảng |
| Thêm | `src/test/.../service/PromotionServiceImplTest.java` | 16 test |
| Thêm | `src/test/.../controller/PromotionControllerTest.java` | 6 test |
| Thêm | `src/test/.../repository/PromotionSpecificationsTest.java` | 2 test H2 |

**Quyết định:**

- Rule chỉ được chứa field đúng loại; field thừa (ví dụ `discountAmount` trong `PERCENT_DISCOUNT`) bị từ chối 400 để engine không phải đoán.
- `targetVariantId` null ở `PERCENT_DISCOUNT`/`FIXED_DISCOUNT` = áp dụng cho mọi line của store (cần TV3 xác nhận khi review IN08).
- `EXPIRED` không đặt tay; promotion hết hạn được ẩn khi đọc dựa trên `endAt`.

### Promotion engine IN08

**Kết quả:** `mvnw.cmd -pl CatalogStoreService test` — **155/155 pass** (thêm 25 test).

| Loại | File | Nội dung |
|---|---|---|
| Thêm | `service/impl/PromotionCalculator.java` | Engine thuần (không Spring/DB): 3 loại khuyến mãi, chọn rule tốt nhất mỗi line, giảm cả đơn vs theo line, phân bổ, làm tròn |
| Thêm | `service/impl/PromotionCalculationServiceImpl.java` | Gộp line trùng, kiểm tra variant/store, nạp giá + promotion hiệu lực theo `Clock`, gọi engine |
| Sửa | `controller/InternalCatalogController.java` | Thêm `POST /internal/v1/promotions/calculate` |
| Sửa | `docs/contracts/README.md` | Contract IN08: request, response, lỗi, quy tắc tính |
| Thêm | `src/test/.../service/PromotionCalculatorTest.java` | 20 test: đủ 3 loại, trigger ≠ reward, reward không có trong giỏ, cùng variant, làm tròn, chọn rule, giảm cả đơn, phân bổ, bất biến tổng |
| Thêm | `src/test/.../service/PromotionCalculationServiceImplTest.java` | 4 test: gộp line, không có promotion, 404, 400 khác store |
| Sửa | `src/test/.../controller/InternalCatalogContractTest.java` | +2 contract test IN08 (full context + H2) |

**Mốc:** toàn bộ backend TV2 (CA01–CA04, CA06–CA09, CA11–CA14, CA16–CA20, IN06–IN08) đã có code và test. Còn lại: API trừ tồn kho (chờ TV3), FE, chạy thử trên SQL Server + Gateway.

### Dọn package controller

- Chuyển `PageRequestFactory` từ `controller/` sang package mới `util/` (`mss.mooncake.catalogstore.util`) để `controller/` chỉ chứa class API. Logic không đổi; cập nhật import ở `CategoryController`, `ProductController`, `ProductVariantController`, `PromotionController`.
- Không dùng `Pageable` mặc định của Spring vì nó tự cắt `size` > max thay vì trả 400 và không có allowlist sort, trái quy ước mục 4.3.
- `mvnw.cmd -pl CatalogStoreService test` — **155/155 pass**.

### Chạy thật trên SQL Server qua Gateway

**Kết quả:** `scripts/catalog-smoke.ps1` — **53/53 kiểm tra pass** trên SQL Server 2022 local (collation `Vietnamese_CI_AS`), luồng đi qua Gateway: đăng ký → supplier onboarding → admin duyệt → store → category → product → variant → search → promotion → IN06/IN07/IN08.

**Lỗi thật phát hiện được (test H2/MockMvc không bắt được):**

| # | Lỗi | Ảnh hưởng | Đã sửa |
|---|---|---|---|
| 1 | Spring Boot 4 tách auto-config Flyway thành module riêng; pom chỉ có `flyway-core` nên **migration không bao giờ chạy**, service vẫn khởi động nhưng DB không có bảng | Cả 4 service | Đã được sửa cho cả 4 pom ở commit `d25b99a` ("fix api name ...") của nhóm trên `origin/main`; bản sửa local trùng nội dung nên đã bỏ, dùng bản trên remote |
| 2 | Gateway `SecurityConfig` chỉ mở public `GET /api/stores`; GET categories/products/promotions bị 403 khi không có JWT | FE không xem được catalog khi chưa đăng nhập | Thêm `permitAll` cho GET catalog trong `API-Gateway/.../SecurityConfig.java` |
| 3 | 403 của Spring Security được forward sang `/error`; JWT filter không chạy lại ở lượt ERROR nên user đã đăng nhập nhận 401 thay vì 403 | Catalog (lộ ra client); Identity cũng bị nhưng Gateway che | Thêm `/error` permitAll trong Catalog `SecurityConfig`. **Identity còn lỗi** khi gọi trực tiếp — báo TV1 |

**File thêm/sửa:**

| Loại | File | Nội dung |
|---|---|---|
| Thêm | `scripts/local-sqlserver-setup.ps1` | Tạo DB `mss_identity`, `mss_catalog_store` + login riêng `mooncake_dev` (db_owner chỉ trên 2 DB) trên SQL Server local; chạy lại an toàn |
| Thêm | `scripts/catalog-smoke.ps1` | Smoke test 53 kiểm tra; `-RegisterAdmin` để tạo admin lần đầu. Lưu UTF-8 **có BOM** để PowerShell 5.1 đọc đúng tiếng Việt |
| Sửa | `API-Gateway/.../config/SecurityConfig.java` | Public GET catalog |
| Sửa | `CatalogStoreService/.../security/SecurityConfig.java` | `/error` permitAll |

**Ghi nhận khác:**

- Search trên SQL Server: có dấu ✅, không phân biệt hoa thường ✅, **không dấu ("banh nuong") không tìm thấy** vì collation `Vietnamese_CI_AS` phân biệt dấu. Muốn hỗ trợ cần chốt với nhóm (collation `_AI` hoặc cột tên chuẩn hóa).
- IN08 trả `subtotal` dạng `410000.00` (scale 2 theo giá DB) nhưng `discountTotal` dạng `86000` (scale 0 sau làm tròn). Giá trị đúng, nhưng nên báo TV3 so sánh bằng giá trị số, không so chuỗi.
- Lệnh tạo secret trong README (`RandomNumberGenerator::GetBytes(32)`) **không chạy trên Windows PowerShell 5.1** (chỉ có ở PowerShell 7+).
- Script PowerShell có tiếng Việt phải lưu UTF-8 có BOM; nếu không PowerShell 5.1 đọc theo ANSI và gửi chuỗi lỗi mã hóa lên server (đã gặp và sửa trong buổi này). DB local còn 1 product thử nghiệm (id 1) tên bị lỗi mã hóa từ lần chạy đó.
- Sau khi thêm starter phải build `clean` — build tăng dần để lại class cũ trong `target/` làm test Identity báo lỗi giả.

### Đưa code về đúng package theo quy ước

Quy ước nhóm (`CODING_CONVENTION_AND_API_RULES.md` dòng 64) chỉ cho phép các package `controller`, `service`, `service.impl`, `repository`, `entity`, `dto.*`, `client`, `exception`, `security`, `config`, `enums`, `websocket`. Các class phụ trợ đã tạo ở các buổi trước nằm ngoài quy ước nên được chuyển vị trí; **logic và API không đổi**.

| Trước | Sau |
|---|---|
| `repository/ProductSpecifications.java` | Static method `publicSearch`, `ownerSearch` trong interface `ProductRepository` |
| `repository/PromotionSpecifications.java` | Static method `publicListing`, `ownerListing` trong interface `PromotionRepository` |
| `service/impl/CatalogMapper.java` | Method private `toVariantResponse`, `toProductResponse`, `toSummary` trong `ProductServiceImpl` / `ProductVariantServiceImpl` (giống `StoreServiceImpl` của TV1) |
| `service/impl/PromotionCalculator.java` | Gộp vào `PromotionCalculationServiceImpl`; phần tính thuần là `static computeDiscounts(...)` package-private |
| `util/PageRequestFactory.java` (package `util` ngoài quy ước) | Record `dto/request/PageQuery(page, size, sort)` + `toPageable(...)`; controller nhận `PageQuery pageQuery` |
| Test `ProductSpecificationsTest`, `PromotionSpecificationsTest`, `PromotionCalculatorTest` | `ProductRepositorySearchTest`, `PromotionRepositoryListingTest`, `service/impl/PromotionDiscountRulesTest` |

**Kết quả:** `mvnw.cmd -pl CatalogStoreService clean package` — **155/155 pass**; `scripts/catalog-smoke.ps1` — **53/53 pass**.

**Cải thiện phụ:** `?page=abc` trước đây trả 500, nay trả **400** (lỗi binding `PageQuery` được `GlobalExceptionHandler` xử lý như lỗi validation).

### Việc tiếp theo

- Báo nhóm 3 lỗi ở trên (đặc biệt Flyway cho Commerce/RFQ và `/error` cho Identity).
- Gửi TV3: contract IN07 + IN08 và quy tắc tính khuyến mãi để review.
- Chạy service với SQL Server thật (kiểm tra migration V3, search tiếng Việt có dấu).
- FE: nối `ProductList`, `ProductDetail`, `BrandStore`, `SellerCenter` với API thật.

---

## 2026-10-22

### Rà soát thay đổi của TV3 trên phần Catalog

Sau commit TV2 `50172c7`, TV3 push `e9e6498` (wip TV3 commerce + cross-service contracts) và `4ebc415` (merge). 27 file trong `CatalogStoreService` thay đổi:

- **IN08 viết lại theo quy tắc nhóm chốt 21/10:** cộng dồn khuyến mãi — một `BUY_X_GET_Y` tốt nhất trên reward line → fixed discount → tổng các percent (tối đa 100%/line); chỉ làm tròn lên tại `totalAmount` của seller order. Response line có `appliedRules[]`. Quy tắc MVP ở Buổi 6 (không cộng dồn, HALF_UP từng line) **không còn áp dụng**. Test Catalog giảm 155 → 145.
- **IN12 giữ tồn kho** (reserve/get/confirm/release, TTL 15 phút) được TV3 thêm vào Catalog, kèm bảng `stock_reservation`, `stock_reservation_line`.
- IN08 chỉ cho `commerce` gọi; `PromotionCalculator.java` xuất hiện lại trong `service/impl`.

### Sửa lỗi chặn: trùng version migration

`V2__stock_reservation.sql` trùng version với `V2__catalog_store_status_constraint.sql` → Catalog **không khởi động được** trên SQL Server (`FlywayException: Found more than one migration with version 2`). Test H2 tắt Flyway nên không phát hiện.

| Loại | File | Nội dung |
|---|---|---|
| Đổi tên | `db/migration/V2__stock_reservation.sql` → `V4__stock_reservation.sql` | Version trống tiếp theo sau V3 seed category. An toàn vì Flyway chặn trùng version trước khi chạy bất kỳ migration nào, nên chưa DB nào áp dụng file cũ |
| Sửa | `CatalogStoreService/docs/TV2_TODO.md`, `docs/CODING_CONVENTION_AND_API_RULES.md` | Đổi "migration V2" của IN12 thành V4 |

**Kết quả:** `mvnw.cmd -pl CatalogStoreService clean package` — 145/145 pass; SQL Server local áp dụng V1–V4 thành công; `scripts/catalog-smoke.ps1` — **53/53 pass** với engine IN08 mới (2 A + 1 B: subtotal 410.000, giảm 86.000, tổng 324.000).

**Ghi nhận:** IN08 mới trả `discountTotal` dạng `86000.000000` (scale 6) — đúng giá trị, consumer nên so sánh số, không so chuỗi.

### Việc tiếp theo

- Báo TV3: đã đổi migration sang V4; DB local của ai đã tạo tay bảng stock thì cần kiểm tra lại.
- TV2: CA14 (PATCH `stockQuantity`) và `stockQuantity` của IN07 chưa trừ lượng đang giữ (hold còn hiệu lực) theo contract IN12.
- Bàn với TV3: đưa `PromotionCalculator` về đúng quy ước package; đối soát `minQuantity`, trigger = reward, giá có số lẻ.

### Buổi 2 (22/10) — Tồn kho theo hold IN12 + test StoreAccessService

**Kết quả:** `mvnw.cmd -pl CatalogStoreService test` — **158/158 pass** (thêm 13 test); `scripts/catalog-smoke.ps1` — **61/61 pass** trên SQL Server (thêm 8 bước tồn kho).

| Loại | File | Nội dung |
|---|---|---|
| Sửa | `repository/StockReservationLineRepository.java` | Thêm `heldQuantities(variantIds, now)` — tổng hold `HELD` chưa hết hạn theo lô (group by), projection `HeldQuantity` |
| Sửa | `dto/response/VariantSnapshotResponse.java` | Thêm `availableQuantity` sau `stockQuantity`; `stockQuantity` vẫn là tồn thực tế (on-hand) theo contract IN12 |
| Sửa | `service/impl/CatalogApplicationServiceImpl.java` | IN07 tính `availableQuantity = max(0, stockQuantity − held)` bằng 1 query cho cả batch; inject `Clock` |
| Sửa | `service/impl/ProductVariantServiceImpl.java` | CA14: khi đổi `stockQuantity` thì khóa dòng variant (cùng cách IN12 reserve) rồi đếm hold; thấp hơn hold → **409** |
| Sửa | `docs/contracts/README.md` | IN07 mô tả `availableQuantity`; Commerce nên dùng field này để kiểm tra còn bán được |
| Thêm | `src/test/.../service/StoreAccessServiceImplTest.java` | 10 test: VERIFIED owner, 404 store, supplier chưa VERIFIED, Identity 404/500, không phải owner, store SUSPENDED, findOwnedStoreIds, isOwner, isPublic |
| Sửa | `ProductVariantServiceImplTest`, `InternalCatalogContractTest`, 3 test promotion | +2 test CA14 hold; +1 contract test hold → IN07 → release; constructor DTO thêm `availableQuantity` |
| Sửa | `scripts/catalog-smoke.ps1` | Bước 8: giữ 5 hộp → IN07 45/50 → CA14 đặt 3 bị 409, đặt 5 được → release → available 5 |

**Lưu ý cho TV3:** Commerce mirror `VariantSnapshotResponse` chưa có `availableQuantity` (field thừa trong JSON được bỏ qua nên không lỗi); khi kiểm tra tồn ở giỏ/checkout nên thêm field này vào DTO mirror và dùng nó thay `stockQuantity`.

### Buổi 3 (22/10) — Giai đoạn 1 FE: lớp gọi API catalog + sửa lỗi phân trang trên SQL Server

**Frontend (`MoonCakeMarketplaceFE`):**

| Loại | File | Nội dung |
|---|---|---|
| Thêm | `src/services/api/http.ts` | axios trỏ Gateway (`VITE_API_BASE_URL`, mặc định `http://localhost:8080`), gắn `Authorization: Bearer` từ `getAccessToken()`; `setAccessToken()` để màn đăng nhập (TV1) lưu token; lỗi chuẩn hóa thành `ApiError(status, message, fieldErrors)` với thông báo tiếng Việt |
| Thêm | `src/services/api/catalogTypes.ts` | Type khớp DTO backend: `PageResponse`, category/product/variant/promotion request/response, status enum |
| Thêm | `src/services/api/catalogApi.ts` | `catalogApi.categories/products/variants/promotions` cho 17 public endpoint của TV2 |
| Sửa | `src/services/api/index.ts` | Export `catalogApi`, `ApiError`, `setAccessToken`, type catalog; ghi rõ `USE_MOCK_API` chỉ còn áp dụng cho address/checkout/order |
| Sửa | `.env.example` | Thêm `VITE_API_BASE_URL` |

`npm run lint` (tsc) và `npm run build` pass. Script gọi thật `catalogApi` qua Gateway: **9/9 pass** (list/search/get/variants/promotions + lỗi 400/401/403/404).

**Backend:**

| Loại | File | Nội dung |
|---|---|---|
| Sửa | `API-Gateway/src/main/resources/application.yml` | CORS mặc định thêm `http://localhost:3000` (FE chạy Express + Vite ở cổng 3000; trước đó preflight bị 403) |
| Sửa | `docs/contracts/README.md` | Ghi cổng FE 3000 được phép CORS |
| **Sửa lỗi** | `CatalogStoreService/.../dto/request/PageQuery.java` | `GET /api/products/{id}/variants` trả **500 trên SQL Server**: sort mặc định `id` + tie-breaker `id` → `ORDER BY variant_id, variant_id` (SQL Server từ chối, H2 chấp nhận). Mọi API gửi `?sort=id` cũng bị. Nay chỉ thêm tie-breaker khi sort chưa có `id` |
| Thêm | `CatalogStoreService/src/test/.../dto/request/PageQueryTest.java` | 3 test: mặc định, không lặp `id`, giới hạn size/page/sort |
| Sửa | `scripts/catalog-smoke.ps1` | Thêm CA11 GET variants, `sort=id,desc`, `sort=id` |

**Kết quả:** Catalog **161/161 test pass**; smoke test **65/65 pass** trên SQL Server.

**Ghi nhận:** Gateway trả **403** (không phải 401) cho request ghi khi chưa đăng nhập — FE nên coi "403 mà chưa có token" là cần đăng nhập. Lỗi CA11 chỉ lộ ra khi FE gọi thật: smoke test trước đó chưa gọi endpoint này.

### Buổi 4 (22/10) — Đưa `PromotionCalculator` về đúng quy ước package (lần 2)

Commit TV3 (`e9e6498`) tạo lại `service/impl/PromotionCalculator.java` với engine IN08 mới, trong khi `service/impl` theo quy ước chỉ chứa `*ServiceImpl` (đã dọn ở Buổi 9 ngày 20/10).

| Trước | Sau |
|---|---|
| `service/impl/PromotionCalculator.java` (instance `calculate(...)`, class phụ `Line`, `RewardCandidate`, `Applied`) | Gộp vào `PromotionCalculationServiceImpl`: phần tính thuần là `static computeDiscounts(request, snapshots, promotions, rules, now)` package-private; class phụ thành nested `private static` |
| `test/.../service/impl/PromotionCalculatorTest.java` | Đổi tên `PromotionComputeDiscountsTest.java`; cùng `PromotionDiscountRulesTest` gọi `PromotionCalculationServiceImpl.computeDiscounts(...)` |

**Logic tính của TV3 giữ nguyên từng dòng** (chỉ đổi method instance → static). `mvnw.cmd -pl CatalogStoreService test` — **161/161 pass**.

**Báo TV3:** engine IN08 nay nằm trong `PromotionCalculationServiceImpl.computeDiscounts`; khi sửa quy tắc tính thì sửa ở đây, không tạo lại class riêng trong `service/impl`.

---

## 2026-10-23

### Buổi 1 (23/10) — Sửa `docker-compose.yml` để chạy backend trong Docker

Giữ thiết kế **mỗi service một SQL Server riêng**; bật một service sẽ tự bật DB của nó.

| Vấn đề trước | Đã sửa |
|---|---|
| Không ai tạo database (`mss_identity`, `mss_catalog_store`, …) → Flyway lỗi | Thêm `<svc>-db-init` chạy `IF DB_ID(...) IS NULL CREATE DATABASE ...` rồi thoát |
| Service bật khi SQL Server chưa sẵn sàng | Healthcheck `sqlcmd SELECT 1`; chuỗi `db (healthy) → db-init (completed) → service`, `restart: on-failure` |
| Catalog không có `IDENTITY_SERVICE_URL` → gọi Identity qua `localhost` trong container | Thêm `http://identity-service:8081` + `depends_on` identity |
| CORS mặc định thiếu cổng FE 3000 | `http://localhost:3000,http://localhost:5173` |
| Dữ liệu mất khi xóa container | Named volume cho 4 DB |
| Gateway chờ cả 4 service | Gateway chỉ phụ thuộc Identity + Catalog |

Mật khẩu SA lấy từ `MSSQL_SA_PASSWORD` trong `.env`. README thêm mục "Docker: toàn bộ backend"; `TODO_IMPLEMENTATION.md` ghi phần đã làm.

**Kiểm tra:** `docker compose config -q` OK. Bản nhỏ (`api-gateway catalog-store-service` → kéo theo Identity + 2 DB): Flyway Identity V1–V3, Catalog V1–V4; smoke test **65/65 pass** qua Gateway trong Docker.

**Chưa kiểm được bản đầy đủ (4 SQL Server + 5 JVM):** máy 7,7 GB RAM, WSL giới hạn 3,2 GB (`.wslconfig`, swap=0). Lúc build Commerce/RFQ, `catalog-db` bị kill (exit 137). Mỗi SQL Server cần ~700 MB nên bản đầy đủ cần khoảng 5–6 GB cho WSL. Commerce/RFQ chưa xác nhận chạy trong Docker.

### Buổi 2 (23/10) — Mô tả + chi nhánh store; FE storefront đọc catalog thật

**Backend (`CatalogStoreService`):**

| Loại | File | Nội dung |
|---|---|---|
| Thêm | `db/migration/V5__store_description_and_branch.sql` | Cột `store.description NVARCHAR(2000)`; bảng `store_branch` (địa chỉ, SĐT, giờ mở cửa, `is_primary`, status `ACTIVE/INACTIVE`); unique index lọc `WHERE is_primary = 1` → mỗi store tối đa 1 chi nhánh chính |
| Sửa | `entity/StoreEntity.java`, `dto/request/CreateStoreRequest.java`, `UpdateStoreRequest.java`, `dto/response/StoreResponse.java`, `service/impl/StoreServiceImpl.java` | Thêm field `description` (tối đa 2000 ký tự, PATCH chỉ đổi khi gửi lên) |
| Thêm | `entity/StoreBranchEntity.java`, `enums/StoreBranchStatus.java`, `repository/StoreBranchRepository.java` | Chi nhánh; `clearPrimary(storeId)` bỏ cờ chính của các chi nhánh khác |
| Thêm | `dto/request/CreateStoreBranchRequest.java`, `UpdateStoreBranchRequest.java`, `dto/response/StoreBranchResponse.java` | DTO chi nhánh |
| Thêm | `service/StoreBranchService.java`, `service/impl/StoreBranchServiceImpl.java` | GET: owner thấy mọi trạng thái, khách chỉ thấy `ACTIVE` của store public, store chưa công bố → 404; ghi chỉ supplier owner (qua `StoreAccessService`); chi nhánh đầu tiên tự là chi nhánh chính |
| Thêm | `controller/StoreBranchController.java` | `GET/POST /api/stores/{storeId}/branches`, `PATCH/DELETE /api/stores/{storeId}/branches/{branchId}`; POST 201 + `Location`, DELETE 204 |
| Thêm | `src/test/.../service/StoreBranchServiceImplTest.java` | 11 test: hiển thị theo quyền, store chưa công khai 404, chi nhánh chính đầu tiên/đổi chính, không phải owner, patch, status sai 400, chi nhánh của store khác 404, xóa |
| Sửa | `StoreControllerTest`, `StoreServiceImplTest` | Cập nhật constructor DTO thêm `description` |
| Thêm | `scripts/seed-demo-catalog.mjs` | Seed demo qua Gateway: 7 gian hàng (Kinh Đô, Givral, Đồng Khánh, Hỷ Lâm Môn, Đức Phát, ABC Bakery, Khiết Minh) — mỗi gian hàng đăng ký supplier → admin duyệt → store + mô tả + chi nhánh → 3 product + variant → 1 promotion. Chạy lại được (bỏ qua supplier đã có store). Dữ liệu chi nhánh/mô tả là minh họa |

**Frontend (`MoonCakeMarketplaceFE`):**

| Loại | File | Nội dung |
|---|---|---|
| Đổi tên | `types.ts`: `Brand` → `Store` | Field theo `StoreResponse` (`storeId`, `slug`, `supplierIdRef`, `logoUrl`, `bannerUrl`); `Product.brandId/brandName` → `storeId/storeName`; `StoreBranch.id` → `branchId`; `Product.category` thành `string` (slug từ API) |
| Thêm | `context/CatalogContext.tsx` | `CatalogProvider` + `useCatalog()`: tải store, product, category một lần qua Gateway, dùng chung cho Home/Navbar/ProductList/danh sách gian hàng. Gateway lỗi hoặc chưa có store → fallback `mockData`; `VITE_USE_MOCK_CATALOG=true` luôn dùng mock. Product chưa có variant `ACTIVE` (không có giá) bị ẩn |
| Thêm | `services/api/catalogMappers.ts` | DTO → view model: `toStore`, `toStoreBranch`, `toProduct`, `withProductDetail`, `toPromotion`, `toCategoryOption`; `isApiId` phân biệt ID số của API với slug mock |
| Thêm / Xóa | `pages/StoreDetail.tsx` thay `pages/BrandStore.tsx` | Trang gian hàng: store từ API tải product, promotion, chi nhánh, category theo `storeId` (promotion/chi nhánh lỗi không làm mất menu); store mock vẫn đọc `mockData` |
| Sửa | `pages/ProductDetail.tsx` | Product từ API gọi `GET /api/products/{id}`: chọn variant (giá, ảnh, hết hàng), giỏ hàng nhận giá/tên variant; ẩn rating/thành phần khi backend chưa có dữ liệu; nút mua disable khi hết hàng |
| Sửa | `App.tsx`, `main.tsx`, `Navbar.tsx`, `CartDrawer.tsx`, `home/StoreCard.tsx`, `home/StoreSection.tsx`, `pages/Home.tsx`, `pages/ProductList.tsx`, `pages/UserProfile.tsx`, `data/mockData.ts`, `services/api/mockBackend.ts` | Đổi `brand` → `store` (tab `brands`/`brand-store` → `stores`/`store-detail`), đọc dữ liệu từ `useCatalog()`; skeleton khi đang tải store; icon lucide `Store` đổi alias `StoreIcon` tránh trùng type |
| Sửa | `services/api/index.ts`, `.env.example` | Export mapper; thêm `VITE_USE_MOCK_CATALOG` |
| Thêm | `public/images/demo/stores/<store>/logo.*` | Logo 7 gian hàng cho script seed |

**Kết quả:** `mvnw.cmd -pl CatalogStoreService test` — **169/169 pass**; FE `npm run lint` (tsc) pass.

**Lưu ý:**

- Store/chi nhánh là phần của TV1 — cần báo TV1 về migration V5, field `description` và API chi nhánh.
- Migration V5 chưa kiểm tra lại trên SQL Server trong buổi này (test H2 tắt Flyway).
- Reviews/rating của product từ API chưa có (thuộc Commerce), FE hiển thị "chưa có đánh giá".

---

## 2026-10-24

### Buổi 1 (24/10) — FE: đăng nhập/đăng ký, phân quyền theo vai trò, onboarding nhà cung cấp, admin duyệt

Chỉ thay đổi ở `MoonCakeMarketplaceFE`; backend không đổi (dùng API Identity sẵn có của TV1 qua Gateway).

| Loại | File | Nội dung |
|---|---|---|
| Thêm | `src/services/api/identityTypes.ts`, `identityApi.ts` | Type + client Identity: `authApi` (register/login), `userApi` (`GET/PATCH /api/users/me`), `supplierProfileApi` (`GET/POST/PATCH /api/supplier-profile`), `adminSupplierApi` (`GET /api/suppliers`, `POST /api/suppliers/{id}/verification`, lịch sử duyệt) |
| Sửa | `src/services/api/http.ts` | `readTokenPayload` / `isTokenExpired` (đọc JWT phía FE, không verify); đọc lỗi validate `validationErrors` của Identity; 401 — hoặc 403 khi token hết hạn (Gateway trả 403 thay vì 401) — xóa token và phát `UNAUTHORIZED_EVENT` để app tự đăng xuất. 403 với token còn hạn là lỗi quyền thật, không đăng xuất |
| Thêm | `src/context/AuthContext.tsx` | `AuthProvider` + `useAuth()`: khôi phục phiên từ token đã lưu, lấy roles từ JWT, profile từ `/api/users/me`, trạng thái supplier từ `/api/supplier-profile` (404 = chưa là supplier); `isAdmin` = `ROLE_ADMIN`, `isSupplier` = profile `VERIFIED`; login, register (tạo xong tự đăng nhập), logout, nộp/nộp lại hồ sơ supplier |
| Thêm | `src/utils/permissions.ts` | Bảng quyền theo tab: `customer` (checkout, orders, profile, wishlist, b2b, payment-settlement), `supplier` (seller-center), `admin` (admin); còn lại public. Chỉ điều khiển UI, backend vẫn kiểm tra |
| Thêm | `src/pages/auth/AuthLayout.tsx`, `Login.tsx`, `Register.tsx` | Màn đăng nhập/đăng ký; đăng ký chọn khách hàng hoặc nhà cung cấp (thêm tên công ty, mã số thuế → nộp hồ sơ ngay sau khi tạo tài khoản); validate phía FE; thông báo lỗi tiếng Việt theo status (401, 403, 409, 400, 5xx) |
| Thêm | `src/pages/seller/SupplierOnboarding.tsx` | Hiện thay Kênh Người Bán khi chưa `VERIFIED`: chưa có hồ sơ → đăng ký; `PENDING` → chờ duyệt; `REJECTED` → sửa và gửi lại; `SUSPENDED` → liên hệ sàn |
| Thêm | `src/pages/admin/AdminSupplierReview.tsx` | Admin duyệt nhà cung cấp: lọc theo trạng thái, phân trang; PENDING → Duyệt/Từ chối, VERIFIED → Tạm khóa, SUSPENDED → Mở khóa; bắt buộc lý do; 409 → báo trạng thái đã đổi |
| Thêm | `src/components/UserMenu.tsx` | Nút đăng nhập/đăng ký khi là khách; menu tài khoản theo vai trò khi đã đăng nhập |
| Sửa | `src/App.tsx` | Tab được bảo vệ hiển thị "cổng": đang kiểm tra phiên → Login (kèm thông báo theo tab) → SupplierOnboarding (tab supplier) → 403 (tab admin). Đăng nhập xong quay lại tab đang mở; đăng ký supplier xong vào Kênh Người Bán; thêm tab `login`, `register`, `admin` |
| Sửa | `src/main.tsx` | Bọc `AuthProvider` ngoài `CatalogProvider` |
| Sửa | `src/components/Navbar.tsx` | Thay nút tài khoản bằng `UserMenu`; menu mobile thêm "Duyệt nhà cung cấp" (admin), Đăng xuất / Đăng ký |
| Sửa | `src/pages/UserProfile.tsx` | Bỏ thông tin cứng; hiển thị user thật + vai trò; lưu họ tên/SĐT qua `PATCH /api/users/me` |
| Sửa | `src/utils/status.ts` | Thêm `SUPPLIER_VERIFICATION` (nhãn + màu cho PENDING/VERIFIED/REJECTED/SUSPENDED) |
| Sửa | `src/services/api/index.ts` | Export API/type Identity và helper token |
| Xóa | `src/features/payment-settlement/LocalFinanceLogin.tsx` (+ dùng trong `PaymentSettlement.tsx`) | Form đăng nhập tạm chỉ dùng khi dev của TV5; nay đã có đăng nhập chung |

**Kết quả:** `npm run lint` (tsc) và `npm run build` pass (chỉ cảnh báo chunk > 500 kB).

**Lưu ý:**

- Màn đăng nhập/đăng ký và Identity là phần của TV1 — cần báo TV1 review. Xóa `LocalFinanceLogin` cần báo TV5.
- Đăng ký nhà cung cấp gồm 2 bước (tạo tài khoản → nộp hồ sơ); nếu bước 2 lỗi thì tài khoản vẫn được tạo và người dùng nộp lại trong Kênh Người Bán.
- Chưa chạy thử luồng trên trình duyệt với backend thật trong buổi này.
