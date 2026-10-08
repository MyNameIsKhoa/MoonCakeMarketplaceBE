# MSS Mooncake Marketplace — TODO triển khai end-to-end cho TV1–TV6

> Tài liệu làm việc để sáu thành viên code backend, frontend, database và test theo từng flow. `TODO.md` hiện có là bản phân công ngắn; file này là checklist triển khai chi tiết. Chưa mục nào được coi là đã hoàn thành chỉ vì class/entity/API skeleton đã tồn tại.

## 0. Nguồn chuẩn và cách đọc checklist

- Business flow: `MSS_Mooncake_Marketplace_Project_Spec_v3_Microservices.docx` và `MSS_4Service_6Member_Workplan_and_Scaffold_v3.md` đã được nhóm cung cấp.
- Physical DB mới nhất: `MSS_Physical_Data_Model_SQLServer_v1.md` cùng `01_mss_identity.sql` → `04_mss_rfq_negotiation.sql`; bốn file này đã được copy vào V1 Flyway migration tương ứng. Nếu ownership/column của tài liệu scaffold cũ khác physical mới thì theo physical mới. Ví dụ: `SUPPLIER_PROFILE` thuộc **Identity**, `STORE` thuộc **Catalog & Store**.
-  Package hỗ trợ: `dto/request`, `dto/response`, `dto/internal`, `entity`, `client/dto`, `config`, `security`, `enums`, `websocket`.
- `[X]` chỉ dùng khi code, API, migration và test liên quan đã chạy được. `[ ]` là việc chưa xong. `Đã có skeleton` nghĩa là có đường dẫn/class nhưng có thể vẫn trả `501` hoặc ném `UnsupportedOperationException`.
- Bảng entity dưới đây liệt kê cột/constraint quan trọng cho việc triển khai, **không thay thế DDL đầy đủ**; khi mapping entity và viết migration phải đối chiếu từng cột, kiểu, nullability, index và FK trong V1 SQL tương ứng.
- Các route ghi **Đề xuất** là danh sách để team thống nhất OpenAPI; chưa phải business rule hay API đã tồn tại. Nếu tài liệu gốc không chốt method, quyền, status transition, payload hoặc chính sách tiền thì ghi **CHỐT**, không tự suy ra.

## 1. Ranh giới hệ thống và quy ước code chung

| Thành phần | Module hiện tại | Package root | Port | DB sở hữu |
|---|---|---|---:|---|
| Gateway | `API-Gateway` | `mss.mooncake.gateway` | 8080 | Không có |
| Identity | `IdentityService` | `mss.mooncake.identity` | 8081 | `mss_identity` |
| Catalog & Store | `CatalogStoreService` | `mss.mooncake.catalogstore` | 8082 | `mss_catalog_store` |
| Commerce | `CommerceService` | `mss.mooncake.commerce` | 8083 | `mss_commerce` |
| RFQ & Negotiation | `RFQService` | `mss.mooncake.rfqnegotiation` | 8084 | `mss_rfq_negotiation` |

- [ ] Giữ đúng **4 business services + 1 Gateway**. TV3/TV5/TV6 cùng sửa Commerce; khác thành viên không có nghĩa là khác service.
- [ ] FE nằm ở sibling repo `D:\Code\Github\MoonCakeMarketplace\MoonCakeMarketFrontend`; FE chỉ dùng base URL của Gateway. Workplan yêu cầu React + TypeScript, trong khi FE hiện đang là JS/JSX; chốt việc chuyển sang TS trước khi cả nhóm cùng mở feature branch.
- [ ] Mỗi HTTP flow theo thứ tự: Controller nhận/validate request và lấy principal → Service kiểm tra quyền, thực thi rule và transaction → Repository truy cập DB sở hữu → Mapper/DTO trả response. Không trả JPA entity ra REST.
- [ ] `@Transactional` chỉ bao các thay đổi **trong một database**. Không giữ DB transaction mở khi gọi Feign; lấy/kiểm tra dữ liệu ngoài service trước khi lưu snapshot, sau đó xử lý lỗi dữ liệu đã thay đổi theo contract đã chốt.
- [ ] Cấm import entity/repository/service implementation của module khác; cấm FK và JPA join vượt service. Cột `*_id_ref` là logical reference, không phải database FK. Dữ liệu hiển thị lịch sử đơn/báo giá lấy từ snapshot, không tính lại theo catalog hiện tại.
- [ ] Call liên service cần kết quả ngay: OpenFeign/REST với timeout, lỗi rõ ràng và xác thực service-to-service. Call TV3 ↔ TV5 ↔ TV6 trong Commerce: gọi Java service trực tiếp.
- [ ] Không thêm Kafka/RabbitMQ chỉ để có “microservices”. WebSocket/STOMP phục vụ chat; event broker/outbox chỉ đưa vào khi nhóm chốt flow bất đồng bộ cụ thể.
- [ ] Không nhận `customerId`, `supplierId`, `actorId` từ body làm bằng chứng quyền sở hữu. Đối chiếu principal/token và dữ liệu owner tại service; bảo vệ cả endpoint `/internal/**`.
- [ ] Chốt chuẩn lỗi API (`400/401/403/404/409/5xx`), phân trang, thời gian UTC/múi giờ hiển thị, định dạng tiền và OpenAPI trước khi FE tích hợp.
- [ ] Viết migration V2+ chỉ khi physical model được nhóm cập nhật/chốt; không sửa V1 đã phát hành. Dùng test SQL Server thật cho migration và constraint quan trọng; H2 `ddl-auto=create-drop` chỉ là smoke test.

### Việc hạ tầng chung trước khi tách branch feature

- [ ] Người giữ Gateway cùng TV1 chốt auth provider và cách JWT được phát hành; Gateway và **từng service** kiểm tra chữ ký/issuer/audience/thời hạn/quyền, sau đó kiểm tra owner tại API nghiệp vụ. Hiện Gateway/Identity đang `permitAll`.
- [ ] Người giữ Gateway chốt CORS hoặc Vite dev proxy cho FE `localhost:5173` → Gateway `localhost:8080`; route `/ws/chat` cho TV4; sửa `/api/supplier-profile/**` về Identity theo physical mới.
- [ ] Người giữ môi trường tạo 4 database trước Flyway, health/readiness cho DB và service, volume lưu SQL Server, mật khẩu local qua env. Compose hiện chỉ chạy container, chưa tự tạo named database.
- [ ] Bật health/metrics/log có request ID cho cả 5 app; chốt timeout Feign và cách báo lỗi upstream. Cloudflare chỉ kiểm tra public endpoint, không thay thế log/metrics nội bộ.
- [ ] Chốt ai review thay đổi `pom.xml`, Gateway, `docker-compose.yml`, contract dùng chung và migration trước khi sáu người cùng sửa.

## 2. Luồng nghiệp vụ xuyên hệ thống

### 2.1. Account → supplier → store → catalog (TV1 + TV2)

1. FE qua Gateway gọi Identity để đăng ký/đọc profile và địa chỉ; Identity lưu `APP_USER`, `CUSTOMER_PROFILE`, `ADDRESS` trong một DB.
2. Supplier onboarding lưu `SUPPLIER_PROFILE` ở Identity; Catalog lưu `STORE.supplier_id_ref` sau khi xác minh supplier qua Identity contract. Không có FK xuyên DB.
3. TV2 tạo category/product/variant/promotion trong Catalog; các rule catalog chỉ đọc/ghi DB Catalog. TV1 và TV2 thống nhất cách kiểm tra store thuộc supplier.
4. `ProductSnapshot`/`StoreSummary` chỉ được các service khác đọc qua internal REST; không truyền entity JPA.

### 2.2. Retail cart → checkout → order (TV2 + TV3 + TV5)

1. Commerce lưu `CART`/`CART_ITEM`; item chỉ giữ `variant_id_ref` và quantity. FE xem giỏ có thể nhận response được Commerce tổng hợp từ Catalog, tránh tự coi giá FE gửi là giá cuối.
2. Checkout lấy customer/address summary từ Identity và product/variant/promotion result từ Catalog bằng Feign. Xác minh owner, tồn kho/giá/khuyến mãi theo contract TV2–TV3 đã chốt.
3. Trong **một Commerce transaction**, tạo `CUSTOMER_ORDER` (source `RETAIL`), `DELIVERY_ADDRESS` snapshot, mỗi store một `SELLER_ORDER`, các `ORDER_ITEM` snapshot tên/SKU/giá/discount. Dùng mã order/seller order ổn định và unique; quy tắc sinh mã là **CHỐT**.
4. Gọi TV5 qua Java service nội bộ để tạo nghĩa vụ `FULL` theo seller order; xử lý callback/idempotency theo TV5. Lịch sử đơn đọc snapshot Commerce, không join sang Catalog khi hiển thị thông tin lúc mua.
5. **CHỐT:** chiến lược đồng bộ tồn kho, trường hợp giá/tồn kho đổi giữa Feign read và commit, tính nhiều promotion, làm tròn tiền, idempotency checkout.

### 2.3. Public RFQ → quotation → accepted order (TV4 + TV3 + TV5)

1. Customer tạo RFQ công khai; supplier tự đọc feed rồi gửi quotation. Không tạo `RFQ_ITEM` hoặc `RFQ_RECIPIENT`.
2. RFQ DB giữ từng quotation version, `previous_quotation_id`, items snapshot, expiry, delivery/shipping/deposit/payment terms. Revision tạo version mới; accepted version không bị ghi đè.
3. Accept kiểm tra customer là owner, version/status/expiry. RFQ gọi `POST /internal/v1/orders/from-accepted-quotation` ở Commerce qua Feign với contract chứa **đủ snapshot terms/items** được chốt. Commerce không tính lại giá accepted quotation từ catalog/promotion.
4. Commerce tạo customer/seller order và `ORDER_ITEM` snapshot; TV5 chỉ tạo `DEPOSIT` sau khi accept thành công. Due của `REMAINING` = ngày giao đã thỏa thuận trừ 3 ngày theo rule MVP.
5. Đây là call qua **hai DB**: `@Transactional` ở RFQ không rollback được Commerce. **CHỐT** idempotency key bền vững, phản hồi lặp lại cùng order, trạng thái khi call timeout/không rõ kết quả và bước đối soát/compensation trước khi code accept. Không retry mù POST.
6. **CHỐT:** một RFQ được accept bao nhiêu quotation, supplier sửa báo giá nào và ai được thấy dữ liệu nào.

### 2.4. Chat, payment, shipment, settlement (TV4 + TV5 + TV6)

1. Chat qua WebSocket/STOMP của RFQ: xác thực kết nối + quyền tham gia conversation và quyền subscribe; persist `CONVERSATION_MESSAGE` trước khi broadcast. Nếu nhiều RFQ instance, phải chốt broker chia sẻ hoặc giới hạn một instance.
2. Payment partner callback tới Commerce: xác minh provider/signature theo provider đã chọn, ghi `PAYMENT` idempotent (`transaction_ref` unique), success thì HELD; không tin status do FE gửi.
3. TV6 cập nhật `SHIPMENT`, delivered/accepted và review trong Commerce; TV5 đọc trạng thái qua Java service nội bộ. Dispute còn blocking thì không settlement. Settlement snapshot fee **5%** và chỉ khi hợp lệ mới RELEASE/payout.
4. Cancel không mặc định refund 100%: policy phụ thuộc actor/time/state; các ngưỡng, quyền ra quyết định và provider refund còn **CHỐT**.

## 3. TV1 — Account, Supplier Onboarding, Store

**Backend:** Identity + phần Store của Catalog. **FE:** `features/account/`, `features/supplier-store/`.

| Entity / DB owner | Cột, quan hệ cần code đúng physical V1 | Việc làm |
|---|---|---|
| `APP_USER` / Identity | `user_id`, `email` unique, `status`, `created_at`, `updated_at` | Repository tìm email/id; đăng ký, trạng thái user, map identity provider đã chốt. |
| `CUSTOMER_PROFILE` / Identity | `customer_id`, `user_id` unique FK local, `full_name`, `phone`, timestamps | Tạo/đọc/cập nhật profile đúng user; phát `UserSummary`. |
| `SUPPLIER_PROFILE` / Identity | `supplier_id`, `user_id` unique FK local, `company_name`, `tax_code` unique, `verification_status`, timestamps | Onboarding, kiểm tra quyền/trạng thái; phát `SupplierSummary` cho Catalog/RFQ/Commerce theo contract. |
| `ADDRESS` / Identity | `address_id`, `customer_id` FK local, recipient/phone/address/ward/district/city, `is_default` | CRUD địa chỉ theo customer owner; kiểm soát default theo rule **CHỐT**; phát address summary để TV3 snapshot. |
| `STORE` / Catalog | `store_id`, `supplier_id_ref` (Identity logical ref), `store_code` unique, `name`, `slug` unique, logo/banner/theme/status | CRUD/storefront/branding; xác minh supplier ở Identity trước thao tác sở hữu; phát `StoreSummary`. |

**API contract (FE → Gateway → owner; mọi dòng đề xuất phải được TV1 và consumer review):**

| API | Trạng thái | Đầu ra / kiểm tra chính |
|---|---|---|
| `POST /api/auth/register` | Đã có skeleton, service chưa implement | RegisterRequest → user/profile summary; cách phát token còn **CHỐT**. |
| `POST /api/auth/login` | **CHỐT** theo auth provider | TODO cũ yêu cầu login; nếu dùng IdP thì FE lấy token theo luồng của IdP, không tự tạo endpoint/password flow ở Identity. |
| `GET/PATCH /api/users/me` | Đề xuất | User/profile của principal, không đọc user tùy ý. |
| `GET/POST /api/addresses`, `PATCH/DELETE /api/addresses/{id}` | Đề xuất | Chỉ địa chỉ thuộc customer của principal; field theo `ADDRESS`, không dùng tên DTO lệch DB một cách ngầm định. |
| `GET/POST/PATCH /api/supplier-profile` | Đề xuất | Onboarding/profile của supplier; Gateway phải route Identity. |
| `GET/POST /api/stores`, `GET/PATCH /api/stores/{id}` | Đề xuất | Catalog quản lý store; quyền sửa theo supplier owner. |
| `GET /internal/v1/users/{userId}/summary` | Đã có skeleton, hiện `501` | TV3/TV4 cần user/customer ID và field snapshot ổn định. |
| `GET /internal/v1/addresses/{id}/summary`, `GET /internal/v1/suppliers/{id}/summary` | Đề xuất | Chỉ service xác thực được gọi; field-level contract TV3/TV4 chốt. |
| `GET /internal/v1/stores/{id}/summary` | Đề xuất, Catalog owner | TV3/TV4 cần store/supplier display snapshot. |

- [ ] Backend: implement controller/service/repository, validation, auth & ownership; thay stub hiện tại bằng logic thật; mapping DTO/entity; lỗi trùng email/tax/slug/code và không tìm thấy ID.
- [ ] DB: dùng đúng V1 Identity/Catalog; nếu cần thêm constraint/index, tạo V2 sau khi cập nhật physical. Không dời `SUPPLIER_PROFILE` sang Catalog.
- [ ] FE: form register/profile/address, supplier onboarding, store/theme; tất cả qua Gateway; xử lý loading/validation/401/403.
- [ ] Test: register trùng email; user A không sửa address B; supplier A không sửa store B; internal summaries đúng contract; migration chạy SQL Server.
- [ ] Handoff: TV2 nhận store ownership; TV3 nhận user/address/store summary; TV4 nhận supplier/user/store summary.
- [ ] **CHỐT:** Keycloak/Auth0/Entra/Okta hay cơ chế khác; login/token; trạng thái verification; quyền admin; cách tạo mã store; status transition.

## 4. TV2 — Catalog, Product, Promotion

**Backend:** Catalog & Store. **FE:** `features/catalog-promotion/`; phối hợp TV1 ở `features/supplier-store/`.

| Entity / DB owner | Cột, quan hệ cần code đúng physical V1 | Việc làm |
|---|---|---|
| `CATEGORY` | `category_id`, `parent_category_id` FK local, `name`, `slug` unique, `status` | Danh mục cha/con, chặn quan hệ không hợp lệ theo rule đã chốt. |
| `PRODUCT` | `product_id`, `store_id` + `category_id` FK local, `(store_id,product_code)` unique, name/description/status/timestamps | CRUD product theo store owner, listing/search; product code vs đổi tên là **CHỐT**, lịch sử order dùng snapshot dù catalog đổi. |
| `PRODUCT_VARIANT` | `variant_id`, `product_id` FK local, `sku` unique, variant name/image, `base_price >= 0`, `stock_quantity >= 0`, status | CRUD variant, snapshot phục vụ TV3/TV4, kiểm tra tồn kho theo contract. |
| `PROMOTION` | `promotion_id`, `store_id` FK local, name, `start_at < end_at`, status | Tạo/chỉnh chương trình giảm giá trong store. |
| `PROMOTION_RULE` | `promotion_rule_id`, `promotion_id` FK local, `target_variant_id`, `reward_variant_id`, rule type, min quantity, percent/amount/reward quantity | Tính `PERCENT_DISCOUNT`, `FIXED_DISCOUNT`, `BUY_X_GET_Y`; trigger/reward có thể khác variant. |

| API | Trạng thái | Đầu ra / kiểm tra chính |
|---|---|---|
| `GET/POST /api/categories`, `GET/PATCH /api/categories/{id}` | Đề xuất | Public read, quản trị write theo quyền được chốt. |
| `GET/POST /api/products`, `GET/PATCH /api/products/{id}` | Đề xuất | Filter/search qua `GET /api/products?...` để dùng route Gateway hiện có. |
| `GET /api/search` | Đề xuất từ `TODO.md`, chưa có Gateway route | Chỉ thêm nếu nhóm muốn endpoint tìm kiếm riêng thay vì query của `/api/products`; chốt filter, pagination và response trước khi code. |
| `GET/POST /api/products/{id}/variants`, `PATCH /api/products/{id}/variants/{variantId}` | Đề xuất | Variant đúng product/store. |
| `GET/POST /api/promotions`, `GET/PATCH /api/promotions/{id}` | Đề xuất | Promotion + rules, chỉ store owner được sửa. |
| `GET /internal/v1/products/{productId}/variants/{variantId}/snapshot` | Đã có skeleton, service chưa implement | TV3/TV4 cần identity, tên, SKU, ảnh/giá theo contract đã chốt. |
| `POST /internal/v1/promotions/calculate` | Đề xuất | Input basket/store/time; output discount chi tiết cho TV3, **CHỐT** field và làm tròn. |

- [ ] Backend: controller/service/repository/DTO/mapper, giới hạn quyền store; tính promotion ở Catalog, không để FE tự tính tổng. Trả DTO snapshot ổn định cho Feign consumers.
- [ ] DB: dùng V1 Catalog; kiểm tra constraint percent/amount/quantity và FK local; không đổi rule BUY_X_GET_Y thành loại khác.
- [ ] FE: storefront, tìm kiếm/filter, product detail/variant, màn supplier quản lý catalog và promotion.
- [ ] Test: cả ba loại promotion; trigger khác reward; hết hạn; product bị sửa nhưng order snapshot cũ không đổi; contract TV3/TV4.
- [ ] Handoff: TV1 xác nhận store owner; TV3 chốt `ProductSnapshot`/`PromotionCalculationResult`; TV4 chốt variant snapshot trong quotation.
- [ ] **CHỐT:** stacking/ưu tiên nhiều promotion, xử lý tồn kho đồng thời, giá/discount rounding, search technology, product code/name/variant rename.

## 5. TV3 — Cart, Checkout, Order

**Backend:** Commerce. **FE:** `features/cart-order/`.

| Entity / DB owner | Cột, quan hệ cần code đúng physical V1 | Việc làm |
|---|---|---|
| `CART` | `cart_id`, `customer_id_ref` Identity logical ref, status/timestamps | Giỏ theo customer; kiểm tra chủ giỏ từ principal. |
| `CART_ITEM` | `cart_item_id`, `cart_id` FK local, `variant_id_ref` Catalog logical ref, `quantity > 0`, unique `(cart_id,variant_id_ref)` | Add/update/remove item, xử lý add lặp và số lượng. |
| `CUSTOMER_ORDER` | `order_id`, `order_no` unique, `customer_id_ref`, `source_type` `RETAIL/RFQ`, `rfq_id_ref`, các tổng tiền, status/placed_at | Master order, tổng nhiều store; RFQ ref chỉ có khi source RFQ. |
| `DELIVERY_ADDRESS` | `delivery_address_id`, `order_id` FK unique local, `source_address_id_ref`, recipient/phone/address/ward/district/city | Snapshot địa chỉ tại checkout, không sửa theo address profile sau này. |
| `SELLER_ORDER` | `seller_order_id`, `order_id` FK local, `seller_order_no` unique, store/supplier/quotation refs, tên snapshot, subtotal/shipping/total, deposit/remaining/due/delivery/shipping/terms snapshot, status | Một seller order mỗi store trong checkout; RFQ order giữ đầy đủ terms đã accept. |
| `ORDER_ITEM` | `order_item_id`, `seller_order_id` FK local, nullable `variant_id_ref`, product/variant/SKU/image snapshot, quantity, unit price/discount/subtotal | Lịch sử sản phẩm/giá theo lúc mua; variant ref nullable cho RFQ custom item. |

| API | Trạng thái | Đầu ra / kiểm tra chính |
|---|---|---|
| `GET /api/cart`, `POST /api/cart/items`, `PATCH/DELETE /api/cart/items/{id}` | Đề xuất | Chỉ cart của principal; response item có display data/giá theo contract. |
| `POST /api/checkout` | Đề xuất | Cart + address/nhận hàng; response order; idempotency **CHỐT**. |
| `GET /api/orders`, `GET /api/orders/{id}` | Đề xuất | Order history/detail từ snapshots, phân quyền buyer/supplier tương ứng. |
| `POST /internal/v1/orders/from-accepted-quotation` | Đã có skeleton, service chưa implement | RFQ → Commerce, idempotent; request hiện thiếu terms/items snapshot nên phải chốt và mở rộng DTO trước khi code. |

- [ ] Backend: cart CRUD; checkout đọc Identity/Catalog bằng Feign, xác thực owner/giá/khuyến mãi; trong một Commerce transaction lưu master, address snapshot, seller orders và items; triển khai RFQ conversion theo **accepted snapshot**, không recalculate Catalog.
- [ ] DB: V1 đã có bảng và local FK; trước migration mới, chốt cách lưu idempotency key và uniqueness của `quotation_id_ref` cho retry.
- [ ] FE: cart, checkout nhiều store, order history/detail đọc snapshot; không để FE gọi trực tiếp nhiều service để tự ghép một quyết định checkout.
- [ ] Test: hai store → một customer order + đúng seller orders; thay product sau mua không thay order detail; order khác owner bị `403/404`; retry RFQ không tạo đơn trùng; migration SQL Server.
- [ ] Handoff: nhận summary TV1 và snapshot/promotion TV2; cung cấp order response cho TV4, seller-order state/context qua Java service cho TV5/TV6.
- [ ] **CHỐT:** mã order, inventory reservation/oversell, transaction & lỗi Feign, idempotency retail checkout, payload accepted quotation từ TV4.

## 6. TV4 — Public RFQ, Quotation, Chat

**Backend:** RFQ & Negotiation. **FE:** `features/rfq-negotiation/`.

| Entity / DB owner | Cột, quan hệ cần code đúng physical V1 | Việc làm |
|---|---|---|
| `RFQ` | `rfq_id`, `rfq_code` unique, `customer_id_ref`, title/description, quantity/budget, requested delivery/location/customization, status/expires/created | Public feed, tạo bởi customer, tìm và kiểm tra hết hạn. |
| `QUOTATION` | `quotation_id`, `rfq_id` FK local, supplier/store logical refs, `previous_quotation_id` FK local, version/total/deposit/validity/delivery/shipping/payment terms/status/submitted/accepted | Supplier gửi, revise thành version mới, compare, accept đúng version còn hiệu lực. |
| `QUOTATION_ITEM` | `quotation_item_id`, `quotation_id` FK local, nullable `variant_id_ref`, item name/SKU/quantity/unit price/discount/subtotal/customization snapshot | Quotation custom item không bắt buộc có SKU hiện tại; giữ giá/tên đã gửi. |
| `CONVERSATION` | `conversation_id`, `rfq_id` FK local, customer/supplier logical refs, status/created, unique `(rfq_id,supplier_id_ref)` | Một conversation cho cặp RFQ/supplier theo physical unique. |
| `CONVERSATION_MESSAGE` | `message_id`, `conversation_id` FK local, `sender_user_id_ref`, type/content/sent_at | Persist trước broadcast; chỉ thành viên conversation đọc/gửi. |

| API / channel | Trạng thái | Đầu ra / kiểm tra chính |
|---|---|---|
| `GET/POST /api/rfqs`, `GET /api/rfqs/{id}` | Đề xuất | Public feed/detail; tạo bởi customer đã xác thực. |
| `GET/POST /api/rfqs/{id}/quotations`, `GET /api/quotations/{id}` | Đề xuất | Supplier submit, buyer compare; quyền xem theo ngữ cảnh. |
| `POST /api/quotations/{id}/revisions` | Đề xuất | Tạo version mới, không ghi đè bản đã accept. |
| `POST /api/quotations/{quotationId}/accept` | Đã có skeleton, service chưa implement | Controller hiện chưa bind/so khớp `quotationId` trong URL với body; sửa contract. |
| `GET /api/conversations/{id}/messages` | Đề xuất | Lịch sử chat chỉ cho participant. |
| `WS /ws/chat` + `/app/...` + `/topic/...` | Mới có config broker, chưa có message handler | Chốt destination/payload/auth; Gateway chưa route WebSocket. |

- [ ] Backend: REST flows, version/expiry/owner checks, Feign summary/snapshot từ TV1/TV2; cùng TV3 chốt accepted quotation payload, idempotency/reconciliation khi Commerce timeout; lưu chat trước broadcast.
- [ ] DB: V1 RFQ có unique version và previous quotation; dùng local FK đúng schema; không thêm `RFQ_ITEM`/`RFQ_RECIPIENT`.
- [ ] FE: public RFQ feed/create, supplier submit/revise, buyer compare/accept, chat + reconnect/load history.
- [ ] Test: version liên tiếp và không ghi đè accepted; hết hạn; quyền supplier/customer; accept lặp trả cùng order; Commerce timeout không âm thầm tạo hai order; chat user ngoài conversation bị chặn.
- [ ] Handoff: TV1/TV2 summaries; TV3 order-create contract; TV5 quy tắc deposit sau accept. Gateway owner route `/ws/chat`.
- [ ] **CHỐT:** số quotation có thể accept trong một RFQ, status transition, timeout/compensation, quyền xem quotation đối thủ, bảo đảm thứ tự message.

## 7. TV5 — Payment, Cancellation/Refund, Dispute, Settlement

**Backend:** các flow trong Commerce. **FE:** `features/payment-settlement/`.

| Entity / DB owner | Cột, quan hệ cần code đúng physical V1 | Việc làm |
|---|---|---|
| `PAYMENT` | `payment_id`, `seller_order_id` FK local, payment_type/amount/due_at/provider, `transaction_ref` unique khi có giá trị, status/paid_at | `FULL`, `DEPOSIT`, `REMAINING`; callback idempotent; success HELD theo business rule. Physical V1 không có cột `hold_status`: chốt cách biểu diễn HELD/RELEASE trước khi code/migration V2. |
| `CANCELLATION` | `cancellation_id`, `seller_order_id` unique FK local, actor_type/actor_id_ref, reason/refund_percent/status/cancelled_at | Xử lý theo actor/time/state/policy; không mặc định hoàn 100%. |
| `REFUND` | `refund_id`, `payment_id` FK local, nullable `cancellation_id` FK local, amount/reason/status/requested/refunded | Gắn khoản đã thanh toán và cancellation khi có; provider refund theo contract. |
| `DISPUTE` | `dispute_id`, `seller_order_id` FK local, opener ref/reason/evidence/status/resolution/opened/resolved | Trạng thái blocking ngăn settlement. |
| `SETTLEMENT` | `settlement_id`, `seller_order_id` unique FK local, gross/`fee_rate` mặc định 5.00/fee amount/payout/status/settled_at | Snapshot phí 5%, kiểm tra delivered/accepted + không có blocking dispute; payout một lần. |

| API | Trạng thái | Đầu ra / kiểm tra chính |
|---|---|---|
| `GET /api/payments?sellerOrderId=...` | Đề xuất | Buyer/supplier chỉ xem payment thuộc order có quyền. |
| `POST /api/payments/{provider}/callback` | **CHỐT** theo payment provider | Xác minh chữ ký và idempotency; Gateway/provider routing riêng, không dùng principal FE thay webhook auth. |
| `POST /api/cancellations`, `GET /api/cancellations/{id}` | Đề xuất | Actor và policy được server xác minh. |
| `GET /api/refunds/{id}` | Đề xuất | Status/amount thực tế. |
| `POST /api/disputes`, `GET /api/disputes/{id}` | Đề xuất | Chỉ actor có quyền; resolution authority **CHỐT**. |
| `GET /api/settlements/{sellerOrderId}` | Đề xuất | Fee/payout/status snapshot. |

- [ ] Backend: payment obligation sau retail checkout hoặc RFQ accept; provider adapter/webhook; `HELD` → `RELEASE` khi settlement; cancellation/refund/dispute policy; settlement eligibility + payout theo seller order.
- [ ] DB: dùng V1; không tự thêm `hold_status`, provider request ID hay status CHECK vào V1. Nếu cần persistent state/idempotency mới, cập nhật physical và thêm V2 sau khi nhóm chốt.
- [ ] FE: payment status, refund/cancellation request, dispute, settlement views; không cho FE tự đặt amount/status.
- [ ] Test: deposit không tạo trước accept; callback trùng không trả tiền hai lần; remaining due = agreed delivery date - 3 days; dispute blocking; fee 5%; payout một lần; refund theo policy đã chốt.
- [ ] Handoff: TV3 cung cấp seller-order payment context bằng **Java service nội bộ**; TV6 cung cấp delivered/accepted bằng Java service nội bộ; TV4 bảo đảm accept trước deposit.
- [ ] **CHỐT:** provider, callback signature, status/hold representation, refund matrix, dispute authority/SLAs, thời điểm settlement, rounding.

## 8. TV6 — Fulfillment, Shipment, Acceptance, Review

**Backend:** các flow trong Commerce. **FE:** `features/fulfillment/`.

| Entity / DB owner | Cột, quan hệ cần code đúng physical V1 | Việc làm |
|---|---|---|
| `SHIPMENT` | `shipment_id`, `seller_order_id` FK local, shipping_method/provider/tracking, expected_ship/shipped/expected_delivery/delivered, shipping_fee/status | Plan → tracking → delivered; dữ liệu ngày và trạng thái phản ánh đúng thực tế. |
| `REVIEW` | `review_id`, `order_item_id` unique FK local, `customer_id_ref`, rating 1..5, comment/created_at | Một review/order item; kiểm tra customer mua item và điều kiện được review. |
| `SELLER_ORDER` (TV3 owner) | status, delivery date, accepted state nằm trong cùng Commerce aggregate | TV6 cập nhật qua service contract đã thống nhất, không tự tạo một seller-order repository/rule khác. |

| API | Trạng thái | Đầu ra / kiểm tra chính |
|---|---|---|
| `GET/POST /api/shipments`, `GET/PATCH /api/shipments/{id}` | Đề xuất | Supplier tạo/cập nhật shipment thuộc seller order của mình; buyer xem tracking. |
| `POST /api/shipments/{id}/delivered` | Đề xuất | Quyền xác nhận và nguồn tín hiệu delivery **CHỐT**. |
| `POST /api/orders/{orderId}/seller-orders/{sellerOrderId}/accept` | Đề xuất | Buyer nghiệm thu seller order thuộc order này; xử lý lặp an toàn. |
| `POST /api/reviews`, `GET /api/reviews?orderItemId=...` | Đề xuất | Chỉ buyer hợp lệ, rating 1..5, một review/item. |

- [ ] Backend: shipment lifecycle/tracking, delivered/accepted, review; cho TV5 đọc eligibility bằng Java service nội bộ.
- [ ] DB: dùng V1 Commerce, local FK và `UQ_review_order_item`; không tạo migration của service mới.
- [ ] FE: supplier fulfillment, buyer tracking/acceptance/review, trạng thái theo seller order.
- [ ] Test: shipment sai owner bị chặn; expected/actual dates; delivered/accept lặp; chưa đủ điều kiện không review; review trùng bị chặn; dispute blocking tới TV5.
- [ ] Handoff: TV3 chốt seller-order status; TV5 chốt tín hiệu nào cho phép settlement. Không Feign giữa TV3/TV5/TV6.
- [ ] **CHỐT:** tự giao hay provider, nguồn xác nhận delivered, acceptance timeout, thời điểm review và status transitions.

## 9. Contract và thứ tự phối hợp

| Contract | Provider → Consumer | Bắt buộc thống nhất trước khi code consumer |
|---|---|---|
| `UserSummary`, `AddressSummary`, `SupplierSummary` | TV1 Identity → TV3/TV4; supplier → TV1 Catalog | ID logical, fields, owner, not-found, auth nội bộ, version. |
| `StoreSummary` | TV1 Catalog → TV3/TV4 | Store/supplier ID, display name snapshot, status. |
| `ProductSnapshot`, `VariantSnapshot` | TV2 Catalog → TV3/TV4 | Product/variant ID, name/SKU/image, giá, trạng thái, lỗi không tìm thấy. |
| `PromotionCalculationResult` | TV2 Catalog → TV3 Commerce | Basket input, discount breakdown, rounding, expiry. |
| `CreateOrderFromAcceptedQuotation` | TV3 Commerce ← TV4 RFQ | Accepted quotation id/version, buyer/supplier/store refs, terms/items snapshots, idempotency, timeout result. **DTO hiện tại mới có ID/version, chưa đủ.** |
| `SellerOrderPaymentContext` | TV3 → TV5 trong Commerce | Java interface/DTO nội bộ; không cần HTTP endpoint. |
| Delivered/Accepted + dispute/settlement status | TV6 ↔ TV5 ↔ TV3 trong Commerce | Java service call, status/event semantics, transaction boundary. |

**Thứ tự tích hợp đề xuất:** Gateway/auth/DB bootstrap → TV1 summaries/store + TV2 snapshots → TV3 retail checkout/order history → TV4 RFQ/accept contract với TV3 → TV5 payment/refund/settlement → TV6 shipment/acceptance/review; TV4 chat có thể làm song song sau khi auth và Gateway WebSocket route đã chốt. Đây là thứ tự để giảm block, không thay đổi phân công TV1–TV6.

## 10. Quy trình mỗi PR và Definition of Done

- [ ] Tạo cùng tên branch feature ở hai repo khi làm một flow end-to-end; PR backend và FE tham chiếu nhau. Không sửa cùng file Commerce/Gateway/migration trên hai branch mà không trao đổi owner trước.
- [ ] Trong PR ghi: service/DB owner, bảng/cột physical dùng, public/internal API OpenAPI, auth/owner check, snapshot lưu ở đâu, idempotency key, lỗi khi service phụ thuộc chết, FE màn hình tương ứng.
- [ ] Unit test cho rule có tiền/trạng thái/quyền; controller/contract test cho request–response; integration test SQL Server cho migration/constraint và happy/failure path. `contextLoads` không đủ để đánh dấu xong feature.
- [ ] Chạy `mvnw.cmd -DskipTests compile`, các test liên quan; FE `npm run lint` và `npm run build`; thử FE → Gateway → service thật. Nếu test bị môi trường JDK/Mockito chặn, ghi log và xử lý riêng, không ghi “test pass”.
- [ ] Khi sửa API, provider cập nhật DTO/OpenAPI trước, consumer cập nhật Feign DTO/FE, rồi merge theo thứ tự thống nhất. Không để frontend dựa vào API route chỉ xuất hiện trong README/TODO.
- [ ] Chỉ đánh dấu `[X]` khi demo được flow với SQL Server, Gateway và frontend, đúng auth/ownership và không tạo dữ liệu trùng khi request lặp.

## 11. Những quyết định cần nhóm/thầy xác nhận trước khi implement

1. Auth provider, token issuer/audience/scopes, cách nhận principal ở service, quyền admin và service-to-service auth.
2. Product/store/order/RFQ code generation; có cho đổi tên product hay không; cách giữ immutable history đã có snapshot.
3. Promotion stacking/rounding và inventory concurrency.
4. Một RFQ accept được bao nhiêu quotation; contract accepted quotation → order; idempotency và đối soát timeout qua hai DB.
5. Payment provider, webhook signature/idempotency; physical V1 chưa có `hold_status` dù business rule yêu cầu HELD/RELEASE.
6. Cancellation/refund matrix, dispute blocking definition, settlement/acceptance timeout.
7. Chat destination, auth/participant check, Gateway WebSocket routing và có cần scale nhiều RFQ instance không.
8. FE chuyển sang TypeScript như workplan hay nhóm chính thức chấp nhận JS; nếu giữ JS phải cập nhật source of truth của nhóm.
9. Bài toán dự đoán mùa cao điểm/sở thích bánh trong ghi chú thầy là **scope mới**: chốt input, output, người dùng và dữ liệu tối thiểu trước khi thêm job/API; chưa sinh service thứ năm.
