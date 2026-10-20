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
2. **Quyền supplier xác minh qua Identity**, không qua role: JWT do Identity phát chỉ có `ROLE_CUSTOMER` (+ `ROLE_ADMIN` nếu nằm trong allowlist), không có `ROLE_SUPPLIER`.
3. **Product, variant, promotion mới đều ở trạng thái `DRAFT`.** Product chỉ được chuyển sang `ACTIVE` khi có ít nhất 1 variant `ACTIVE`.
4. **Search dùng Specification** (Spring Data JPA 4.1: `Specification.allOf(...)`, không dùng `where(null)` cũ). Ký tự `%`/`_` trong từ khóa được escape; danh sách store rỗng trả kết quả rỗng thay vì lỗi `IN ()`.
5. **Giả định MVP của promotion engine** (ghi trong Javadoc, cần TV3 review): mỗi line chỉ áp dụng một rule có lợi nhất, tiền VND làm tròn `HALF_UP`, tổng giảm không vượt subtotal.

### Việc cần trao đổi

- **TV1:** TV2 dùng `StoreAccessService` riêng thay vì sửa `StoreService`. Cả hai cùng sửa `SecurityConfig`, nên báo trước khi đổi file này.
- **TV3:** review DTO `VariantSnapshotResponse` và `PromotionCalculationRequest/Result` khi bắt đầu Commerce; chốt cách trừ tồn kho khi checkout.
- **SQL Server:** search có phân biệt dấu hay không phụ thuộc collation; cần kiểm tra lại khi chạy DB thật.

### Việc tiếp theo

Category (seed V3 + `CategoryServiceImpl` + `CategoryController`), rồi đến Product → Variant → Snapshot IN06/IN07. Chi tiết trong [`TV2_TODO.md`](../CatalogStoreService/docs/TV2_TODO.md).
