# Backend thành viên 4 — RFQ, báo giá và chat

## 1. Phạm vi

Backend triển khai trong `RFQService`, kết nối Identity/Catalog/Commerce bằng OpenFeign và đi qua Gateway cổng **8080**. Frontend xem mục 8.

RFQ là yêu cầu mua hàng để các nhà cung cấp gửi báo giá. Một phiên bản báo giá giữ riêng sản phẩm, giá và điều khoản đã gửi; revision tạo bản ghi mới.

```text
Buyer → Gateway → RFQ Controller → Service → Repository → SQL Server
Supplier → gửi báo giá → Buyer so sánh → chấp nhận phiên bản
RFQ → lưu intent PENDING → Commerce tạo đơn và khoản thanh toán
    → RFQ lưu COMPLETED + orderId/sellerOrderId
```

## 2. Quyết định áp dụng cho lần triển khai này

| Nội dung | Quy tắc đã triển khai |
|---|---|
| Công bố | Tạo RFQ chuyển ngay thành `OPEN`; gửi báo giá chuyển ngay thành `SUBMITTED`. Không có luồng bản nháp/publish/submit riêng. |
| Gửi riêng | Không gửi `targetStoreIds` (hoặc rỗng) → `audience=ALL`, lên sàn chung như trước. Gửi 1–20 cửa hàng `ACTIVE` → `audience=SELECTED_STORES`: RFQ không lên sàn chung; chỉ chủ RFQ và nhà cung cấp sở hữu cửa hàng được mời xem, báo giá, nhắn tin. Nhà cung cấp khác nhận `404 RFQ_NOT_FOUND` như RFQ không tồn tại; nhà cung cấp được mời báo giá từ cửa hàng không được mời → `403 STORE_NOT_TARGETED`; chủ RFQ mở hội thoại với nhà cung cấp không được mời → `403 SUPPLIER_NOT_TARGETED`. Chủ RFQ thấy toàn bộ `targetStores`, nhà cung cấp chỉ thấy cửa hàng của mình. Danh sách cố định sau khi tạo. |
| Sửa RFQ | Chỉ owner, RFQ mở/còn hạn và chưa có bất kỳ báo giá nào. PATCH phân biệt field vắng với `null`. |
| Đóng/hủy RFQ | CLOSED dừng nhận báo giá mới, vẫn accept báo giá đã gửi còn hiệu lực. Hết hạn RFQ dừng intake, hiệu lực báo giá theo validUntil. CANCELLED chặn accept mới; giữ đơn và intent đã hình thành. Hủy đơn thuộc Commerce. |
| Phiên bản | Server tăng version theo RFQ/cửa hàng. Revision chỉ từ bản mới nhất đang `SUBMITTED`, chưa có conversion; RFQ phải `OPEN` và còn hạn. Bản cũ thành `REVISED`, giữ nguyên item/giá/điều khoản. |
| Nhiều báo giá | Có thể accept nhiều báo giá của một RFQ, mỗi báo giá tạo một customer order và seller order riêng, nhưng tổng số lượng (tổng `quantity` các item) của báo giá đang/đã chuyển thành đơn không vượt `rfq.quantity`; vượt thì `409 RFQ_QUANTITY_EXCEEDED`. Đủ số lượng: RFQ tự `CLOSED` (`auto_closed`) và các báo giá `SUBMITTED` còn lại thành `REJECTED` ("Không được chọn"). |
| Đơn bị hủy | Job mỗi 60 giây (`app.order-sync.delay-ms`) đọc trạng thái đơn của báo giá đã accept từ Commerce. Đơn `CANCELLED` → báo giá `ORDER_CANCELLED`, không còn tính vào số lượng; RFQ do hệ thống tự đóng thì mở lại `OPEN`. RFQ người mua tự đóng (kể cả đóng lại sau khi hệ thống đã đóng) không mở lại. Khi mở lại, báo giá bị loại vì "đủ số lượng" mà còn hiệu lực trở lại `SUBMITTED`. `GET /api/quotations/{id}/order` trả thêm `orderStatus`. Flyway V10. |
| Nhà cung cấp hợp lệ | Tạo/sửa báo giá yêu cầu hồ sơ `VERIFIED` (403 `SUPPLIER_NOT_VERIFIED`); accept báo giá của nhà cung cấp bị khóa/chưa xác minh → 409 `SUPPLIER_NOT_VERIFIED`. Không tự báo giá cho RFQ của chính tài khoản, không accept báo giá của chính mình (403 `SELF_DEALING`). |
| Ràng buộc khác | Tổng số lượng item của một báo giá không vượt `rfq.quantity` (409 `QUOTATION_EXCEEDS_RFQ_QUANTITY`). `expiresAt` phải trước ngày `requestedDeliveryDate` (giờ VN). Báo giá còn tiền phải trả cần ngày giao ≥ hôm nay + 6 ngày (48 giờ chờ cọc + hạn phần còn lại 3 ngày trước giao). Accept với địa chỉ không tồn tại → 404 `ADDRESS_NOT_FOUND`. |
| Hủy RFQ | Không hủy được khi đã có báo giá `ACCEPTED` hoặc đang tạo đơn (409 `RFQ_HAS_ORDERS`, hãy đóng thay vì hủy). Hủy thì các báo giá `SUBMITTED` thành `REJECTED`. Nhà cung cấp có báo giá `ACCEPTED` vẫn mở được hội thoại sau khi RFQ đóng. |
| Snapshot | Item tùy chỉnh có thể không có variant. Item dùng Catalog phải thuộc đúng product/store/supplier, dùng tên/SKU từ Catalog và giá thương lượng từ request. |
| Tổng tiền | Item subtotal = quantity × unitPrice − discountAmount; subtotal nội bộ khi tạo đơn = tổng item subtotal; totalAmount = subtotal + shippingFee. Response báo giá trả `totalAmount` và subtotal từng item. |
| Làm tròn | Chốt theo TV5: tiền báo giá (đơn giá, giảm giá, phí vận chuyển) là **VND nguyên** — gửi `"90000"` hoặc `"90000.00"`, phần lẻ đồng bị từ chối `400 AMOUNT_NOT_WHOLE_VND`; percent tối đa 2 chữ số thập phân. Đặt cọc = total × percent / 100, `HALF_UP` về **đồng** (vd 30% của 1.005 = 302). Commerce cũng từ chối snapshot có phần lẻ đồng, vì mọi dòng `payment` phải là số nguyên (`CK_payment_integer_vnd`). Ngân sách RFQ chỉ để tham khảo nên vẫn nhận tối đa 2 chữ số thập phân. |
| Thanh toán | Conversion đăng ký đơn với TV5 qua `FinanceIntegrationService.syncOrder` (cùng transaction, giống checkout lẻ): TV5 tạo `finance_context` và các khoản thanh toán payOS, TV4 không tự ghi bảng `payment`. Cọc làm tròn nằm **giữa 0 và tổng đơn** → `DEPOSIT` + `REMAINING`; cọc 0%, 100% hoặc làm tròn chạm 0/tổng → một khoản `FULL` (đơn `AWAITING_PAYMENT`, `depositAmount`/`remainingAmount` để trống như đơn lẻ). `REMAINING` đến hạn 00:00 giờ Việt Nam, 3 ngày trước ngày giao. Người mua xem/khởi tạo thanh toán ở `GET /api/payments/orders/{sellerOrderId}`. |
| Địa chỉ | Buyer chọn addressId; RFQ xác minh customer owner qua Identity và lưu snapshot bất biến trong intent. |
| Hết hiệu lực | Báo giá `SUBMITTED` quá `validUntil` được trả về với `status=EXPIRED` (tính khi đọc, không ghi DB); `GET /api/quotations?status=EXPIRED|SUBMITTED` lọc theo đúng trạng thái hiển thị này. Supplier vẫn revise được bản mới nhất để gia hạn. |
| Retry | Tạo/revise/accept báo giá yêu cầu `Idempotency-Key`. Dùng lại cùng key và cùng body; đổi body bị `409`. Keys phân biệt hoa/thường. |
| Conversion | Lưu snapshot và intent trước gọi Commerce; lookup/retry dùng cùng key. `202 PENDING` có thể tra bằng endpoint `/order`. Worker phục hồi mỗi 30 giây sau restart. Commerce từ chối hẳn (400/409/422) và lookup xác nhận chưa có đơn → intent `FAILED`, ngừng retry; accept trả `409 QUOTATION_CONVERSION_FAILED`, `/order` trả `conversionStatus=FAILED`. Báo giá vẫn `SUBMITTED` để supplier revise/rút hoặc buyer từ chối. |
| Chat | Một hội thoại/RFQ/supplier; chỉ participant được đọc/gửi/subscribe. Mở hội thoại mới yêu cầu supplier `VERIFIED` (`409 SUPPLIER_NOT_VERIFIED`); hội thoại đã có vẫn đọc được. TEXT tối đa 10.000 ký tự, clientMessageId chống lưu trùng. Broadcast sau commit. JWT dùng lúc CONNECT hết hạn thì SEND/SUBSCRIBE bị từ chối, client phải kết nối lại với token mới. |

Các thay đổi không thuộc TV4 như payment provider/webhook/refund/settlement, catalog CRUD và stock reservation tiếp tục theo phần phân công riêng.

## 3. API đã triển khai

| Method | URL | Quyền/chức năng |
|---|---|---|
| GET | `/api/rfqs` | Public search (ẩn RFQ `OPEN` đã quá `expiresAt`; detail vẫn đọc được) gồm RFQ `ALL` và RFQ gửi riêng mời cửa hàng của người gọi; `mine=true` cần buyer đăng nhập. |
| GET | `/api/rfqs/{rfqId}` | Public detail; owner thấy private fields. |
| POST | `/api/rfqs` | Buyer tạo/công bố, trả 201 và Location; `targetStoreIds` (mảng ID string) tùy chọn để gửi riêng. |
| PATCH | `/api/rfqs/{rfqId}` | Owner sửa trước báo giá, Content-Type merge-patch. |
| POST | `/api/rfqs/{rfqId}/close`, `/cancel` | Owner đóng/hủy RFQ. |
| GET/POST | `/api/rfqs/{rfqId}/quotations` | Buyer so sánh; supplier đọc của mình/gửi báo giá. |
| GET | `/api/quotations` | Dashboard supplier, chỉ hỗ trợ mine=true. |
| GET | `/api/quotations/{quotationId}` | Buyer RFQ owner/supplier owner. |
| POST | `/api/quotations/{quotationId}/revisions` | Supplier tạo phiên bản mới, expectedVersion. |
| POST | `/api/quotations/{quotationId}/accept` | Buyer accept, quotationVersion và addressId. |
| POST | `/api/quotations/{quotationId}/reject`, `/cancel` | Buyer từ chối/supplier rút; reason tùy chọn được lưu. |
| GET | `/api/quotations/{quotationId}/order` | Owner tra PENDING/COMPLETED và IDs của đơn. |
| POST | `/api/rfqs/{rfqId}/conversations` | Buyer chọn supplierId; supplier dùng hồ sơ của mình. |
| GET | `/api/conversations`, `/api/conversations/{id}` | Hội thoại của participant. |
| GET | `/api/conversations/{id}/messages` | Cursor trước/sau, limit 1..100, mặc định 50. |
| GET | `/api/conversations/unread-count` | `{unreadCount}`: tin của bên kia sau lần đọc cuối (FE hiện badge trên thanh menu). |
| POST | `/api/conversations/{id}/read` | Participant đánh dấu đã đọc tới tin mới nhất; không lùi; 204. |

Collection dùng page từ 0, size mặc định 20/tối đa 100; response `{items,page,size,totalElements,totalPages}`. Sort có allowlist và ID làm thứ tự phụ.

Public RFQ response ẩn customerId, deliveryLocation và customizationRequirements với người không phải owner. Public RFQ không chứa quotation/chat.

## 4. Ví dụ request

Các API JSON mới dùng **ID và tiền là string**, quantity/version là integer, timestamp có UTC offset. JWT vào header `Authorization: Bearer ...`; danh tính người thực hiện luôn lấy từ token và Identity.

### Tạo RFQ

```http
POST /api/rfqs
Content-Type: application/json
Authorization: Bearer <buyer-token>
```

```json
{
  "title": "Đặt bánh trung thu cho công ty",
  "quantity": 100,
  "budgetAmount": "10000000.00",
  "requestedDeliveryDate": "2026-11-01",
  "description": "Bánh có hộp quà",
  "expiresAt": "2026-10-25T10:00:00Z"
}
```

### Gửi báo giá tùy chỉnh

```http
POST /api/rfqs/1/quotations
Idempotency-Key: quote-request-001
Authorization: Bearer <supplier-token>
Content-Type: application/json
```

```json
{
  "storeId": "1",
  "depositPercent": "30.00",
  "validUntil": "2026-10-26T10:00:00Z",
  "proposedDeliveryDate": "2026-11-01",
  "shippingMethod": "DELIVERY",
  "shippingFee": "50000.00",
  "items": [
    {
      "itemNameSnapshot": "Hộp bánh theo yêu cầu",
      "quantity": 100,
      "unitPrice": "90000.00",
      "discountAmount": "0.00"
    }
  ]
}
```

Với item Catalog, truyền thêm `productId` và `variantId` dạng string. Supplier phải sở hữu cửa hàng đang ACTIVE; quy trình xác minh supplier trước tạo cửa hàng vẫn do Identity/Catalog quản lý.

Mỗi báo giá cần **1–50 items**. Các field `storeId`, `depositPercent`, `validUntil`, `proposedDeliveryDate`, `shippingFee` và `items` là bắt buộc; ngày giao không được ở quá khứ, `validUntil` phải ở tương lai khi tạo phiên bản mới. Cọc nằm giữa 0% và 100% (có phần còn lại, hạn 3 ngày trước ngày giao) thì ngày giao phải cách hôm nay (giờ Việt Nam) ít nhất 4 ngày: tạo/sửa phiên bản sai → `400 DELIVERY_TOO_SOON_FOR_REMAINING`, chấp nhận báo giá đã quá sát ngày → `409 DELIVERY_TOO_SOON_FOR_REMAINING`; message nêu ngày sớm nhất được phép. Mục tùy chỉnh bỏ cả `productId`/`variantId` và cần `itemNameSnapshot`; `discountAmount` không gửi được tính là `0.00`. Mục Catalog phải có cả hai ID và store/product/variant đều `ACTIVE`.

Revision gửi các field điều khoản/items như tạo báo giá, **bỏ `storeId` và thêm `expectedVersion`**; dùng `Idempotency-Key` riêng cho lần sửa. Response báo giá/danh sách dùng field **`version`**; body accept dùng **`quotationVersion`**. Khi cửa hàng đã có báo giá mới nhất `SUBMITTED`, tạo báo giá khác trả `409`; phải sửa bằng revision.

Reject/cancel chỉ áp dụng báo giá `SUBMITTED` chưa bắt đầu conversion. Gửi lại cùng quyết định và cùng `reason` trả kết quả đã lưu; đổi `reason` trả `409`. Hai endpoint này không yêu cầu header `Idempotency-Key`.

### Chấp nhận báo giá

```http
POST /api/quotations/1/accept
Idempotency-Key: accept-request-001
Authorization: Bearer <buyer-token>
Content-Type: application/json
```

```json
{"quotationVersion": 1, "addressId": "1"}
```

Hoàn tất trả `200`, với `conversionStatus=COMPLETED`, orderId và sellerOrderId. Nếu chưa biết kết quả Commerce, trả `202` với `conversionStatus=PENDING`; GET `/api/quotations/1/order` để đọc trạng thái. Retry không đổi key/body, kể cả sau khi báo giá hết hạn nếu intent đã được lưu.

### PATCH

```http
PATCH /api/rfqs/1
Content-Type: application/merge-patch+json
```

```json
{"description": null, "title": "Tiêu đề mới"}
```

Field không gửi giữ nguyên. `null` chỉ xóa field nullable; quantity/title không được null. ID, owner, status và unknown fields bị từ chối.

## 5. WebSocket/STOMP

```text
Browser → ws://localhost:8080/ws/chat → Gateway MVC proxy → RFQ :8084
CONNECT: Authorization: Bearer <jwt>
SUBSCRIBE: /topic/conversations/{conversationId}
SEND: /app/conversations/{conversationId}/messages
```

```json
{"clientMessageId":"message-001","messageType":"TEXT","content":"Xin trao đổi thêm về mẫu hộp"}
```

Không gửi token trong query URL. Gateway/RFQ giới hạn origin; `CHAT_ALLOWED_ORIGINS` và `CORS_ALLOWED_ORIGINS` phải khớp với địa chỉ frontend. Sender lấy từ principal, khác với customerId/supplierId.

Hội thoại trả thêm `customerName`: tên người mua lưu vào RFQ lúc tạo (`rfq.customer_name_snapshot`, Flyway V9) để nhà cung cấp biết đang trao đổi với ai. RFQ tạo trước V9 trả `null`; FE hiện "Người mua".

History trả `{items,nextCursor,hasMore}`; items tăng dần theo messageId. Request không có cursor lấy nhóm tin mới nhất; beforeMessageId tải tin cũ, afterMessageId tải tin mới. Reconnect lấy history và dedup messageId. Retry cùng clientMessageId/nội dung trả và phát lại tin đã lưu; khác nội dung trả conflict.

Broker hiện là in-memory: chạy một RFQ instance. Chưa có attachment/read receipt/broker cluster.

## 6. Migration và kiểm tra

- RFQ V2: quotation decision/command idempotency; V3: durable conversion; V4: chat dedup; V5: acceptance key collation; V6: trạng thái conversion `FAILED`; V7: `rfq.audience` + bảng `rfq_target_store` (gửi riêng cửa hàng); V8: bảng `conversation_read_state` (tin đã đọc của từng participant).
- Commerce V5: unique quotation conversion; V6: opaque key collation (V2/V3/V4 thuộc cart uniqueness, TV5 finance và retail checkout). Order/address/items/payment tạo trong một transaction.
- Các business service dùng Spring Boot 4 `spring-boot-starter-flyway` để migration thực sự chạy khi khởi động.
- Catalog mapping được đối chiếu SQL Server V1: audit chỉ có ở PRODUCT; STORE/VARIANT không được truy vấn các cột audit chưa tồn tại.

```powershell
mvn test
pwsh -File scripts/verify-tv4-sqlserver.ps1
```

Xem [Docker local setup](DOCKER_LOCAL_SETUP.md) để chạy SQL Server và backend. Test SQL Server dùng database riêng, không xóa dữ liệu chính.

### Demo xuyên các service đang chạy

Sau khi năm service và bốn DB Docker đã sẵn sàng, chạy `scripts/smoke-tv4-http.ps1 -FixtureAccountsConfirmed`, rồi `java scripts/SmokeTv4Chat.java` với hội thoại trả về từ HTTP. [Hướng dẫn Docker, mục 5](DOCKER_LOCAL_SETUP.md#5-chạy-demo-http-và-websocket-qua-gateway) có các biến môi trường và cách nhập token bằng prompt ẩn.

Hai script này dùng **buyer/supplier thử nghiệm riêng, mới tạo**, với địa chỉ của buyer và cửa hàng `ACTIVE` của supplier; có thể thêm token của supplier thử nghiệm thứ hai để kiểm tra quyền đọc của bên ngoài. HTTP tạo RFQ, báo giá/revision, hội thoại và **đơn demo chưa thanh toán** trong database ứng dụng. Commerce tạo nghĩa vụ thanh toán `PENDING`; không gọi cổng thanh toán. Mỗi lần chạy tạo dữ liệu mới và không tự xóa dữ liệu demo. Chat kiểm tra broadcast hai participant, lưu tin trước khi phát, gửi lại không tạo tin trùng và lịch sử qua Gateway.

Các nhóm kiểm tra bao gồm ownership/privacy, schema JSON, giá/phiên bản/expiry, create/revision cạnh tranh, accept/replay/recovery, snapshot/payment rollback, chat dedup/cursor/commit và WebSocket thực qua Gateway.

## 7. Debug lỗi thường gặp

| Hiện tượng | Kiểm tra |
|---|---|
| 401 | JWT thiếu/sai/hết hạn; STOMP token phải nằm trong CONNECT header. |
| 403 | UserId khác customerId/supplierId; địa chỉ/cửa hàng/hội thoại phải đúng owner. |
| 400 | Gửi tiền/ID là string; quantity/version là integer; thiếu Idempotency-Key hoặc có unknown field. |
| 409 | RFQ/báo giá hết hạn, phiên bản cũ, khóa dùng lại với body khác, revision sau conversion, hoặc cửa hàng inactive. |
| 202 PENDING | Commerce chưa được xác nhận; tra `/order`, kiểm tra logs RFQ/Commerce và service tokens. |
| Lỗi SQL invalid object/column | Kiểm tra Flyway history, starter-flyway và mapping; không chạy V1 thủ công lên DB đã migrate. |
| WS không handshake | Origin allowlist, Gateway `/ws/chat`, container RFQ và endpoint; không dùng URL query token. |
| WS đọc được của người khác | SUBSCRIBE cũng phải qua kiểm tra membership phía server; không chỉ kiểm tra SEND. |

## 8. Frontend TV4 (repo MoonCakeMarketplaceFE)

- Tab **Doanh Nghiệp / B2B** (`src/pages/B2BPortal.tsx`) mở `src/features/rfq/RfqPortal.tsx`: sàn RFQ, RFQ của tôi, so sánh/chấp nhận/từ chối báo giá, soạn báo giá và phiên bản mới, báo giá của tôi, tin nhắn. Chat ở `src/features/chat/` (STOMP tối giản trên WebSocket gốc, không thêm dependency).
- Gọi API qua `src/services/api/http.ts`; base URL `VITE_API_BASE_URL` (mặc định `http://localhost:8080`). FE dev chạy cổng **3000** (`npm run dev`), nên Gateway cần `CORS_ALLOWED_ORIGINS=http://localhost:5173,http://localhost:3000` (cũng dùng cho origin WebSocket).
- Đăng nhập dùng chung `src/context/AuthContext.tsx` của TV1: tab B2B ở mức `customer` (`src/utils/permissions.ts`) nên khách được chuyển tới màn Login chung; `RfqPortal` lấy user/hồ sơ nhà cung cấp từ `useAuth()`, REST và chat dùng cùng token trong `http.ts` (401/token hết hạn sẽ đăng xuất toàn app).
- Các mock RFQ còn lại trong `SellerCenter` (tab RFQ) và `UserProfile` (tab RFQ) thuộc trang của thành viên khác, chưa nối API; có thể chuyển hướng sang tab B2B.

## 9. Kịch bản demo (UI)

### Chuẩn bị (một lần)

1. Tạo `.env` từ `.env.example` và đặt `CORS_ALLOWED_ORIGINS=http://localhost:3000,http://localhost:5173` (FE dev chạy cổng 3000), rồi `docker compose up -d`.
2. Có admin trong `ADMIN_USER_IDS` (xem README mục Docker), rồi chạy `node scripts/seed-demo-catalog.mjs`: tạo các nhà cung cấp `VERIFIED`, gian hàng `ACTIVE` và sản phẩm. FE chưa có màn tạo/kích hoạt gian hàng nên bước này bắt buộc.
3. Chạy `node scripts/seed-demo-tv4.mjs`: tạo buyer doanh nghiệp có sẵn địa chỉ và kiểm tra hai nhà cung cấp demo đã sẵn sàng gửi báo giá. Chạy lại được; không cần quyền admin. Biến `GATEWAY_URL`, `BUYER_PASSWORD`, `SUPPLIER_PASSWORD` đổi mặc định.
4. FE: `npm run dev` trong repo MoonCakeMarketplaceFE, mở `http://localhost:3000`. Nếu Gateway không ở cổng 8080, đặt `VITE_API_BASE_URL`.

| Vai trò | Tài khoản mặc định |
|---|---|
| Buyer | `buyer.b2b@mooncake.local` / `MoonCake@Buyer2026` |
| Nhà cung cấp gửi báo giá | `supplier.kinh-do@mooncake.local` / `MoonCake@Supplier2026` |
| Nhà cung cấp khác (đối thủ) | `supplier.givral@mooncake.local` / `MoonCake@Supplier2026` |

Mở buyer và nhà cung cấp ở **hai cửa sổ trình duyệt riêng** (một cửa sổ ẩn danh): token đăng nhập lưu trong `localStorage`, dùng chung giữa các tab cùng trình duyệt.

### Các bước (khoảng 10 phút)

| # | Ai | Thao tác | Điểm cần nhấn mạnh |
|---|---|---|---|
| 1 | Buyer | Đăng nhập → tab **Doanh Nghiệp** → **Tạo yêu cầu báo giá** (tiêu đề, số lượng, ngân sách, ngày giao) | Công bố ngay (`OPEN`); địa điểm giao và yêu cầu tùy chỉnh chỉ chủ RFQ thấy |
| 2 | Kinh Đô | **Sàn yêu cầu báo giá** → mở RFQ → **Gửi báo giá** (cọc 30%, đơn giá, phí ship) | Tiền là VND nguyên; mục từ catalog lấy tên/SKU từ Catalog, giá theo thương lượng |
| 3 | Kinh Đô | Sửa báo giá → phiên bản 2 với giá mới | Bản cũ thành "Đã có phiên bản mới" nhưng giữ nguyên giá đã gửi |
| 4 | Buyer | Mở RFQ → **So sánh báo giá** → **Xem chi tiết** | Givral đăng nhập không đọc được báo giá của Kinh Đô |
| 5 | Cả hai | Buyer **Nhắn tin với nhà cung cấp**, hai bên nhắn qua lại | Chat thời gian thực qua Gateway; tin hiện ngay ở cửa sổ kia |
| 6 | Buyer | **Chấp nhận** phiên bản 2 → chọn địa chỉ (hoặc **Thêm địa chỉ mới**) | Hiện "Đơn hàng #… (đơn nhà bán #…)"; báo giá thành "Đã chấp nhận" |
| 7 | Buyer | Tab **Thanh Toán** → nhập **mã đơn nhà bán** | TV5 có `DEPOSIT` + `REMAINING` (VND nguyên, hạn 00:00 giờ Việt Nam 3 ngày trước ngày giao), hoặc một khoản `FULL` khi cọc 0%/100% |

Có thể demo thêm: buyer từ chối báo giá, nhà cung cấp rút báo giá, hoặc nhập số tiền lẻ đồng để thấy `AMOUNT_NOT_WHOLE_VND`.

### Lưu ý

- Tạo link payOS ở bước 7 cần khóa `PAYOS_*` trong `.env`; không có khóa thì chỉ demo tới danh sách khoản thanh toán.
- API trả thời gian theo UTC (`17:00Z` là 00:00 giờ Việt Nam hôm sau).
- Mỗi lần demo tạo RFQ/đơn mới trong database; dữ liệu không tự xóa.

