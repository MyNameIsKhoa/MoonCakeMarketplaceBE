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
| Sửa RFQ | Chỉ owner, RFQ mở/còn hạn và chưa có bất kỳ báo giá nào. PATCH phân biệt field vắng với `null`. |
| Đóng/hủy RFQ | CLOSED dừng nhận báo giá mới, vẫn accept báo giá đã gửi còn hiệu lực. Hết hạn RFQ dừng intake, hiệu lực báo giá theo validUntil. CANCELLED chặn accept mới; giữ đơn và intent đã hình thành. Hủy đơn thuộc Commerce. |
| Phiên bản | Server tăng version theo RFQ/cửa hàng. Revision chỉ từ bản mới nhất đang `SUBMITTED`, chưa có conversion; RFQ phải `OPEN` và còn hạn. Bản cũ thành `REVISED`, giữ nguyên item/giá/điều khoản. |
| Nhiều báo giá | Có thể accept nhiều báo giá của một RFQ. Mỗi báo giá tạo một customer order và seller order riêng; chưa có gộp đơn/phân bổ tổng quantity. |
| Snapshot | Item tùy chỉnh có thể không có variant. Item dùng Catalog phải thuộc đúng product/store/supplier, dùng tên/SKU từ Catalog và giá thương lượng từ request. |
| Tổng tiền | Item subtotal = quantity × unitPrice − discountAmount; subtotal nội bộ khi tạo đơn = tổng item subtotal; totalAmount = subtotal + shippingFee. Response báo giá trả `totalAmount` và subtotal từng item. |
| Làm tròn | Chốt theo TV5: tiền báo giá (đơn giá, giảm giá, phí vận chuyển) là **VND nguyên** — gửi `"90000"` hoặc `"90000.00"`, phần lẻ đồng bị từ chối `400 AMOUNT_NOT_WHOLE_VND`; percent tối đa 2 chữ số thập phân. Đặt cọc = total × percent / 100, `HALF_UP` về **đồng** (vd 30% của 1.005 = 302). Commerce cũng từ chối snapshot có phần lẻ đồng, vì mọi dòng `payment` phải là số nguyên (`CK_payment_integer_vnd`). Ngân sách RFQ chỉ để tham khảo nên vẫn nhận tối đa 2 chữ số thập phân. |
| Thanh toán | Chỉ sau conversion tạo `DEPOSIT` và `REMAINING` ở Commerce. Khoản bằng 0 được bỏ qua; tổng đơn phải lớn hơn 0. Phần còn lại đến hạn 00:00 UTC trước ngày giao 3 ngày. |
| Địa chỉ | Buyer chọn addressId; RFQ xác minh customer owner qua Identity và lưu snapshot bất biến trong intent. |
| Hết hiệu lực | Báo giá `SUBMITTED` quá `validUntil` được trả về với `status=EXPIRED` (tính khi đọc, không ghi DB); `GET /api/quotations?status=EXPIRED|SUBMITTED` lọc theo đúng trạng thái hiển thị này. Supplier vẫn revise được bản mới nhất để gia hạn. |
| Retry | Tạo/revise/accept báo giá yêu cầu `Idempotency-Key`. Dùng lại cùng key và cùng body; đổi body bị `409`. Keys phân biệt hoa/thường. |
| Conversion | Lưu snapshot và intent trước gọi Commerce; lookup/retry dùng cùng key. `202 PENDING` có thể tra bằng endpoint `/order`. Worker phục hồi mỗi 30 giây sau restart. Commerce từ chối hẳn (400/409/422) và lookup xác nhận chưa có đơn → intent `FAILED`, ngừng retry; accept trả `409 QUOTATION_CONVERSION_FAILED`, `/order` trả `conversionStatus=FAILED`. Báo giá vẫn `SUBMITTED` để supplier revise/rút hoặc buyer từ chối. |
| Chat | Một hội thoại/RFQ/supplier; chỉ participant được đọc/gửi/subscribe. Mở hội thoại mới yêu cầu supplier `VERIFIED` (`409 SUPPLIER_NOT_VERIFIED`); hội thoại đã có vẫn đọc được. TEXT tối đa 10.000 ký tự, clientMessageId chống lưu trùng. Broadcast sau commit. JWT dùng lúc CONNECT hết hạn thì SEND/SUBSCRIBE bị từ chối, client phải kết nối lại với token mới. |

Các thay đổi không thuộc TV4 như payment provider/webhook/refund/settlement, catalog CRUD và stock reservation tiếp tục theo phần phân công riêng.

## 3. API đã triển khai

| Method | URL | Quyền/chức năng |
|---|---|---|
| GET | `/api/rfqs` | Public search (ẩn RFQ `OPEN` đã quá `expiresAt`; detail vẫn đọc được); `mine=true` cần buyer đăng nhập. |
| GET | `/api/rfqs/{rfqId}` | Public detail; owner thấy private fields. |
| POST | `/api/rfqs` | Buyer tạo/công bố, trả 201 và Location. |
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

Mỗi báo giá cần **1–50 items**. Các field `storeId`, `depositPercent`, `validUntil`, `proposedDeliveryDate`, `shippingFee` và `items` là bắt buộc; ngày giao không được ở quá khứ, `validUntil` phải ở tương lai khi tạo phiên bản mới. Mục tùy chỉnh bỏ cả `productId`/`variantId` và cần `itemNameSnapshot`; `discountAmount` không gửi được tính là `0.00`. Mục Catalog phải có cả hai ID và store/product/variant đều `ACTIVE`.

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

History trả `{items,nextCursor,hasMore}`; items tăng dần theo messageId. Request không có cursor lấy nhóm tin mới nhất; beforeMessageId tải tin cũ, afterMessageId tải tin mới. Reconnect lấy history và dedup messageId. Retry cùng clientMessageId/nội dung trả và phát lại tin đã lưu; khác nội dung trả conflict.

Broker hiện là in-memory: chạy một RFQ instance. Chưa có attachment/read receipt/broker cluster.

## 6. Migration và kiểm tra

- RFQ V2: quotation decision/command idempotency; V3: durable conversion; V4: chat dedup; V5: acceptance key collation; V6: trạng thái conversion `FAILED`.
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

