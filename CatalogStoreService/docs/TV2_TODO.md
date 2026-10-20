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
| Quyền supplier | JWT không có `ROLE_SUPPLIER`; luôn xác minh `userId` → Identity supplier summary → so `store.supplierIdRef`. Supplier phải `VERIFIED` |
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
- [x] Repository query cho 5 bảng; search product bằng `ProductSpecifications`
- [x] `SecurityConfig`: GET catalog public, ghi category chỉ admin
- [x] `StoreAccessService(Impl)`: kiểm tra chủ store
- [x] Service interface: `CategoryService`, `ProductService`, `ProductVariantService`, `PromotionService`, `PromotionCalculationService`, `CatalogApplicationService` (thêm IN07)
- [x] Request/response DTO cho toàn bộ API của TV2
- [x] Test `ProductSpecificationsTest` (5 test, H2)

**Chưa có:** toàn bộ service impl (trừ `StoreAccessServiceImpl`), controller public CA*, IN07/IN08 controller, migration seed, test service/controller.

## 4. Checklist theo thứ tự làm

Thứ tự theo phụ thuộc dữ liệu: `product.category_id` bắt buộc → làm Category trước; Variant cần Product; Promotion cần Variant.

### Bước 2 — Category (~0.5 ngày)

- [ ] `db/migration/V3__seed_categories.sql`: seed 5 danh mục gốc (truyền thống, hiện đại, chay, cao cấp, thiếu nhi), slug khớp FE
- [ ] `CategoryServiceImpl`
  - [ ] CA01 `GET /api/categories?parentCategoryId=&page=&size=`: danh mục gốc hoặc con trực tiếp, chỉ `ACTIVE`
  - [ ] CA02 `GET /api/categories/{categoryId}`: không tồn tại hoặc `INACTIVE` trả 404
  - [ ] CA03 `POST /api/categories`: slug trùng 409, parent phải tồn tại
  - [ ] CA04 `PATCH /api/categories/{categoryId}`: chặn chu trình parent, slug trùng 409
- [ ] `CategoryController`
- [ ] Test: slug trùng, parent tự trỏ chính nó, parent là con cháu, non-admin bị 403

### Bước 3 — Product (~1.5 ngày)

- [ ] `ProductServiceImpl`
  - [ ] CA06 `GET /api/products?q=&storeId=&categoryId=&mine=&page=&size=&sort=`: dùng `ProductSpecifications.publicSearch` / `ownerSearch`; `mine=true` không đăng nhập thì 401; sort allowlist (`name`, `createdAt`, `id`), sort lạ trả 400
  - [ ] `ProductSummaryResponse.minPrice/imageUrl` lấy từ variant `ACTIVE`, load theo lô bằng `findByProductIdInOrderByIdAsc` (tránh N+1)
  - [ ] CA07 `GET /api/products/{productId}`: kèm variants; người ngoài chỉ thấy variant `ACTIVE`
  - [ ] CA08 `POST /api/products`: `requireOwnedStore`, category `ACTIVE`, sinh productCode, status `DRAFT`
  - [ ] CA09 `PATCH /api/products/{productId}`: owner; đổi status theo quy tắc mục 2
- [ ] `ProductController` cho `/api/products`. Lưu ý: `ProductController` hiện có đang map `/internal/v1/products`, nên tách controller internal riêng (ví dụ `InternalCatalogController`) để không lẫn route
- [ ] Test: supplier A không sửa product của B (403); supplier chưa `VERIFIED` (400); category `INACTIVE` (400); public không thấy `DRAFT` (404); `ACTIVE` khi chưa có variant (400)

### Bước 4 — Variant (~1 ngày)

- [ ] `ProductVariantServiceImpl`
  - [ ] CA11 `GET /api/products/{productId}/variants`
  - [ ] CA12 `GET /api/products/{productId}/variants/{variantId}`: dùng `findByIdAndProductId`, sai product trả 404
  - [ ] CA13 `POST /api/products/{productId}/variants`: SKU trùng 409, status `DRAFT`
  - [ ] CA14 `PATCH /api/products/{productId}/variants/{variantId}`: SKU mới trùng 409; không cho tắt variant `ACTIVE` cuối cùng khi product đang `ACTIVE` (hoặc tự chuyển product về `INACTIVE` — chốt một cách, ghi lại)
- [ ] Endpoint đặt trong `ProductController` hoặc tách `ProductVariantController`
- [ ] Test: SKU trùng, giá/tồn kho âm (400), variant sai product (404)

### Bước 5 — Snapshot cho TV3/TV4 (~0.5 ngày)

- [ ] IN06 `CatalogApplicationServiceImpl.getProductSnapshot`: kiểm tra variant thuộc product, 404 nếu sai
- [ ] IN07 `POST /internal/v1/variants/snapshots`: giữ thứ tự đầu vào, gộp ID trùng, thiếu ID trả 404 kèm danh sách ID thiếu; load product + store theo lô
- [ ] Cập nhật `docs/contracts/README.md`: thêm IN07, mô tả field `VariantSnapshotResponse`, quyền caller (`commerce`, `rfq`)
- [ ] Contract test: JSON field name đúng contract, 404, caller không có service token bị chặn
- [ ] Báo TV3/TV4 contract đã sẵn sàng

### Bước 6 — Promotion CRUD (~1 ngày)

- [ ] `PromotionServiceImpl`
  - [ ] CA16 `GET /api/promotions?storeId=&mine=&page=&size=`
  - [ ] CA17 `GET /api/promotions/{promotionId}`: kèm rules
  - [ ] CA18 `POST /api/promotions`: owner, `startAt < endAt`, kiểm tra field theo `ruleType`, `countInStore` khớp số variant
  - [ ] CA19 `PATCH /api/promotions/{promotionId}`: name/thời gian/status (`DRAFT`, `ACTIVE`, `DISABLED`)
  - [ ] CA20 `PUT /api/promotions/{promotionId}/rules`: `deleteByPromotionId` + insert mới trong cùng `@Transactional`
- [ ] `PromotionController`
- [ ] Test: rule thiếu field theo loại (400), variant khác store (400), `startAt >= endAt` (400), non-owner (403)

### Bước 7 — Promotion engine IN08 (~1.5 ngày)

- [ ] Class thuần `PromotionCalculator` (không phụ thuộc Spring) để unit test
- [ ] `PromotionCalculationServiceImpl`: load variant + promotion hiệu lực (`findEffective`) + rules, gọi calculator
- [ ] `POST /internal/v1/promotions/calculate`
- [ ] Quy tắc tính MVP:
  - `PERCENT_DISCOUNT`: giảm `lineSubtotal × percent / 100` trên line target (target null = mọi line của store)
  - `FIXED_DISCOUNT`: giảm số tiền cố định trên line target, không vượt line subtotal
  - `BUY_X_GET_Y`: số lần áp dụng = `floor(qtyTarget / minQuantity)`; giảm `min(lần × rewardQuantity, qtyReward) × giá reward` trên line reward; reward phải có trong giỏ
  - Mỗi line nhận tối đa 1 rule có lợi nhất; làm tròn VND `HALF_UP` theo line; `discountTotal ≤ subtotal`
- [ ] Unit test: đủ 3 loại; trigger khác reward; reward không có trong giỏ; nhiều bội số X; promotion hết hạn/chưa bắt đầu/`DRAFT`; variant khác store (400); làm tròn
- [ ] Ghi contract IN08 vào `docs/contracts/README.md`, gửi TV3 review

### Bước 8 — Chờ TV3 (chưa code)

- [ ] Đề xuất API internal trừ tồn kho: `UPDATE ... SET stock_quantity = stock_quantity - :qty WHERE variant_id = :id AND stock_quantity >= :qty`, có idempotency key. Chỉ ghi vào contract, code khi TV3 chốt flow checkout

### Bước 9 — Frontend (`MoonCakeMarketplaceFE`)

- [ ] Thay `src/services/api/mockBackend.ts` bằng API thật cho `ProductList`, `ProductDetail`, `BrandStore`
- [ ] `pages/seller/SellerCenter.tsx`: quản lý product/variant/promotion
- [ ] Bỏ phần tự tính khuyến mãi ở FE; giá cuối do server tính

## 5. Điểm còn phải CHỐT

| # | Câu hỏi | Đề xuất hiện tại | Chốt với |
|---|---|---|---|
| 1 | Nhiều promotion cùng áp dụng | Mỗi line 1 rule có lợi nhất, không cộng dồn | TV3, nhóm |
| 2 | Làm tròn tiền | VND, scale 0, `HALF_UP`, theo line | TV3, TV5 |
| 3 | `FIXED_DISCOUNT` khi `targetVariantId` null | Giảm trên tổng đơn của store, phân bổ theo line | TV3 |
| 4 | Trừ tồn kho / chống bán quá | UPDATE có điều kiện + idempotency key | TV3 |
| 5 | Xóa product/variant/promotion (CA10, CA15, CA21) | Chưa làm; dùng `INACTIVE`/`DISABLED` thay vì xóa | Nhóm |
| 6 | Search không dấu trên SQL Server | Kiểm tra collation khi chạy DB thật; nếu cần thì thêm cột/chuẩn hóa sau | Nhóm |
| 7 | Tắt variant `ACTIVE` cuối cùng của product đang `ACTIVE` | Chặn (400) | Tự quyết, ghi lại |

## 6. Definition of Done cho mỗi bước

- Logic nằm ở `service/impl`; controller chỉ nhận/validate request, lấy `userId` từ `@AuthenticationPrincipal UserPrincipal`, không nhận `supplierId`/`userId` từ body
- Không trả entity ra REST; dùng DTO record
- Có unit test cho rule quyền/trạng thái/tiền và controller test cho request–response
- `mvnw.cmd -pl CatalogStoreService test` pass toàn bộ (kể cả test của TV1)
- Cập nhật `docs/NguyenHuuDat.md` và tick checklist trong file này
