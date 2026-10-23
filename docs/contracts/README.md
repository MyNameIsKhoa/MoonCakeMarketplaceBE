# Internal API contracts — v1

This file is the source of truth for the internal summaries implemented in the current backend. Each endpoint is owned by the service listed below. Consumer DTOs live in the consuming service and mirror the provider JSON; services do not share JPA entities or implementation classes.

## Summary endpoints

| Contract / owner | Endpoint and consumers | Response fields (JSON names) | Not found |
|---|---|---|---|
| User summary / Identity | `GET /internal/v1/users/{userId}/summary`; Commerce, RFQ | `userId` (Identity `APP_USER.user_id`), `customerId` (nullable), `fullName` (nullable), `email`, `phone` (nullable) | `404` when `userId` does not exist. A user without a customer profile still returns `200`; profile fields are `null`. |
| Address summary / Identity | `GET /internal/v1/addresses/{id}/summary`; Commerce, RFQ | `addressId`, `customerId`, `recipientName`, `recipientPhone`, `addressLine`, `ward` (nullable), `district` (nullable), `city` | `404` when the address ID does not exist. RFQ verifies buyer ownership before persisting the acceptance snapshot. |
| Supplier summary / Identity | `GET /internal/v1/suppliers/{id}/summary` or `/by-user/{userId}/summary`; Catalog, Commerce, RFQ | `supplierId`, `userId`, `companyName`, `taxCode`, `verificationStatus` | `404` when the supplier ID or user ID has no supplier profile. |
| Store summary / Catalog & Store | `GET /internal/v1/stores/{id}/summary`; Commerce, RFQ | `storeId`, `supplierIdRef` (Identity supplier ID), `storeCode`, `name`, `slug`, `logoUrl` (nullable), `status` | `404` when the store ID does not exist. This internal summary can include non-public statuses. |

All paths use `/internal/v1`. The version belongs to the URI; there is no `version` property in the JSON body. Add a `/v2` endpoint for a breaking field or meaning change, and keep `/v1` compatible while consumers migrate. JSON property names and ID ownership above are part of the contract.

## Internal authentication and errors

Callers send both `X-Service-Name` and `X-Service-Token`. The name must match the caller registered by the receiving service, and the token must be the dedicated secret for that caller/provider pair. Names are `catalog`, `commerce`, and `rfq`. Current endpoint permissions are:

| Identity endpoint | Accepted callers |
|---|---|
| User summary | `commerce`, `rfq` |
| Address summary | `commerce`, `rfq` |
| Supplier summary | `catalog`, `commerce`, `rfq` |
| Catalog store/product summaries, variant snapshots (IN06, IN07), promotion calculation (IN08) | `commerce`, `rfq` |
| Commerce internal order/payment-context endpoints | `rfq` |

Clients should branch on the HTTP status, not parse an error message. A missing summary is always HTTP `404`; Identity returns its `ErrorResponse` (`status`, `error`, `message`, `path`, optional `details`), while Catalog returns its service error body (`status`, `message`, `timestamp`). Invalid internal credentials do not grant the endpoint authority.

## Public routing through Gateway

The Gateway owns external HTTP routing and CORS. Identity routes include auth, users, addresses, admin operations, and a dedicated `/api/supplier-profile/**` route to Identity. Store/catalog paths route to Catalog; order/payment paths to Commerce; RFQ/quotation/chat paths to RFQ. `/internal/**` is not exposed as a Gateway route.

Browser development can call Gateway from `http://localhost:5173`. Configure `CORS_ALLOWED_ORIGINS` as a comma-separated list for other explicit FE origins. The Gateway allows credentialed requests with the supported API methods and headers. This repository has no FE code or Vite configuration; when the FE is added, it should use the Gateway base URL and keep its own Vite proxy in the FE repository if preferred.

## Other internal contracts

| Contract / owner | Endpoint / consumer | Current versioned payload |
|---|---|---|
| Product variant snapshot (IN06) / Catalog | `GET /internal/v1/products/{productId}/variants/{variantId}/snapshot`; Commerce, RFQ | `productId`, `variantId`, `productName`, `variantName`, `sku`, `agreedUnitPrice` (= Catalog `base_price` hiện tại; tên field giữ nguyên vì consumer đã mirror); additive fields `storeId`, `supplierId`, `productCode`, `imageUrl`, `status`. `404` khi variant không thuộc `productId`. Trả cả variant không ACTIVE. Negotiated quotation price remains supplied by the quotation, not replaced with catalog price. |
| Variant snapshots batch (IN07) / Catalog | `POST /internal/v1/variants/snapshots`; Commerce (RFQ cũng được gọi) | Request `{"variantIds":[...]}`, 1–100 ID. Response là mảng `VariantSnapshotResponse` theo **thứ tự đầu vào, ID trùng được gộp**: `variantId`, `productId`, `storeId`, `supplierId` (Identity supplier ID), `productCode`, `productName`, `variantName`, `sku`, `imageUrl` (nullable), `basePrice`, `stockQuantity`, `variantStatus`, `productStatus`, `storeStatus`. Trả mọi trạng thái: caller phải tự kiểm tra cả ba status là `ACTIVE` trước khi bán. Có ID không tồn tại → `404`, `message` liệt kê ID thiếu; danh sách rỗng hoặc > 100 → `400` |
| Promotion calculation (IN08) / Catalog | `POST /internal/v1/promotions/calculate`; Commerce | Request `{"storeId":1,"lines":[{"variantId":11,"quantity":2}]}`, 1–100 line, `quantity > 0`; line trùng variant được cộng dồn. **Không nhận giá từ caller**: giá = `base_price` hiện tại, thời điểm tính = giờ UTC của Catalog, chỉ dùng promotion `ACTIVE` có `startAt <= now < endAt`. Response `PromotionCalculationResult`: `storeId`, `calculatedAt`, `subtotal`, `discountTotal`, `totalAmount`, `lines[]` (`variantId`, `quantity`, `unitPrice`, `lineSubtotal`, `discountAmount` = tổng giảm của cả line, `lineTotal`, `promotionId`?, `promotionRuleId`?), `appliedPromotions[]` (`promotionId`, `promotionName`, `promotionRuleId`, `ruleType`, `discountAmount`, `rewardVariantId`?/`rewardQuantity`? chỉ với `BUY_X_GET_Y`). Lỗi: variant không tồn tại `404`; variant khác store `400`. Không kiểm tra ACTIVE/tồn kho (dùng IN07). **Quy tắc tính MVP (chờ TV3 xác nhận):** mỗi line tối đa một rule (giảm nhiều nhất, hòa thì `promotionRuleId` nhỏ hơn); `FIXED_DISCOUNT` không target = giảm cả đơn, không cộng dồn với rule theo line (chọn phương án giảm nhiều hơn, phân bổ theo tỷ lệ); `BUY_X_GET_Y` giảm trên line reward, reward phải có trong giỏ; tiền giảm làm tròn VND `HALF_UP`, không vượt subtotal |
| Create order from accepted quotation / Commerce | `POST /internal/v1/orders/from-accepted-quotation`; RFQ | `CreateOrderFromQuotationRequest` → `OrderResponse` |
| Seller-order payment context / Commerce | `GET /internal/v1/seller-orders/{sellerOrderId}/payment-context`; RFQ/internal Commerce flow | `SellerOrderPaymentContext` |

Product snapshot `status` is `ACTIVE` only when the store, product and variant are all `ACTIVE`. Otherwise it reports the first unavailable status in that order (for example `SUSPENDED`, `DRAFT` or `OUT_OF_STOCK`); RFQ refuses a new Catalog-backed item with a non-active snapshot. Missing product/variant/store or a variant with the wrong product parent returns 404. The snapshot reads current Catalog data and does not reserve stock.

### Accepted quotation conversion — implemented TV4 contract

POST `/internal/v1/orders/from-accepted-quotation` takes matching RFQ/Commerce snapshot DTOs: rfqId, quotationId, quotationVersion, acceptedAt (UTC stored datetime), customerId, supplierId, storeId, idempotencyKey, storeSnapshot, deliveryAddressSnapshot, terms, items, subtotal and totalAmount. These internal IDs/decimals retain the existing numeric wire representation; public TV4 DTOs use strings. Snapshot semantics and public examples are in [TV4_BACKEND_GUIDE.md](../TV4_BACKEND_GUIDE.md).

Commerce returns `{orderId,status,sellerOrderId,quotationId,quotationVersion}` with HTTP 200 for both creation and replay. It atomically persists a unique quotation/key claim, customer/seller order, address/items and positive DEPOSIT/REMAINING obligations. A changed snapshot/key for an existing quotation returns 409. GET `/internal/v1/orders/by-quotation/{quotationId}` returns the committed result or 404; 404 does not authorize retry with a new key. RFQ always retries its persisted snapshot and stable key.

RFQ address-summary access now accepts the dedicated `rfq` service credential. Internal routes remain absent from Gateway routing. Gateway supports `/ws/chat` via a WebSocket bridge with STOMP authentication at RFQ; CORS includes `Idempotency-Key`.
