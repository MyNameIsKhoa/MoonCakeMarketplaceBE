# Internal API contracts — v1

This file is the source of truth for the internal summaries implemented in the current backend. Each endpoint is owned by the service listed below. Consumer DTOs live in the consuming service and mirror the provider JSON; services do not share JPA entities or implementation classes.

## Summary endpoints

| Contract / owner | Endpoint and consumers | Response fields (JSON names) | Not found |
|---|---|---|---|
| User summary / Identity | `GET /internal/v1/users/{userId}/summary`; Commerce, RFQ | `userId` (Identity `APP_USER.user_id`), `customerId` (nullable), `fullName` (nullable), `email`, `phone` (nullable) | `404` when `userId` does not exist. A user without a customer profile still returns `200`; profile fields are `null`. |
| Address summary / Identity | `GET /internal/v1/addresses/{id}/summary`; Commerce | `addressId`, `customerId`, `recipientName`, `recipientPhone`, `addressLine`, `ward` (nullable), `district` (nullable), `city` | `404` when the address ID does not exist. |
| Supplier summary / Identity | `GET /internal/v1/suppliers/{id}/summary` or `/by-user/{userId}/summary`; Catalog, Commerce, RFQ | `supplierId`, `userId`, `companyName`, `taxCode`, `verificationStatus` | `404` when the supplier ID or user ID has no supplier profile. |
| Store summary / Catalog & Store | `GET /internal/v1/stores/{id}/summary`; Commerce, RFQ | `storeId`, `supplierIdRef` (Identity supplier ID), `storeCode`, `name`, `slug`, `logoUrl` (nullable), `status` | `404` when the store ID does not exist. This internal summary can include non-public statuses. |

All paths use `/internal/v1`. The version belongs to the URI; there is no `version` property in the JSON body. Add a `/v2` endpoint for a breaking field or meaning change, and keep `/v1` compatible while consumers migrate. JSON property names and ID ownership above are part of the contract.

## Internal authentication and errors

Callers send both `X-Service-Name` and `X-Service-Token`. The name must match the caller registered by the receiving service, and the token must be the dedicated secret for that caller/provider pair. Names are `catalog`, `commerce`, and `rfq`. Current endpoint permissions are:

| Identity endpoint | Accepted callers |
|---|---|
| User summary | `commerce`, `rfq` |
| Address summary | `commerce` |
| Supplier summary | `catalog`, `commerce`, `rfq` |
| Catalog store/product summaries and variant snapshots (IN06, IN07) | `commerce`, `rfq` |
| Catalog promotion calculation (IN08) | `commerce` only |
| Catalog inventory reservations | `commerce` only |
| Commerce internal order/payment-context endpoints | `rfq` |

Clients should branch on the HTTP status, not parse an error message. A missing summary is always HTTP `404`; Identity returns its `ErrorResponse` (`status`, `error`, `message`, `path`, optional `details`), while Catalog returns its service error body (`status`, `message`, `timestamp`). Invalid internal credentials do not grant the endpoint authority.

## Public routing through Gateway

The Gateway owns external HTTP routing and CORS. Identity routes include auth, users, addresses, admin operations, and a dedicated `/api/supplier-profile/**` route to Identity. Store/catalog paths route to Catalog; order/payment paths to Commerce; RFQ/quotation/chat paths to RFQ. `/internal/**` is not exposed as a Gateway route.

Browser development can call Gateway from `http://localhost:5173`. Configure `CORS_ALLOWED_ORIGINS` as a comma-separated list for other explicit FE origins. The Gateway allows credentialed requests with the supported API methods and headers. This repository has no FE code or Vite configuration; when the FE is added, it should use the Gateway base URL and keep its own Vite proxy in the FE repository if preferred.

## Other internal contracts

| Contract / owner | Endpoint / consumer | Current versioned payload |
|---|---|---|
| Product variant snapshot (IN06) / Catalog | `GET /internal/v1/products/{productId}/variants/{variantId}/snapshot`; Commerce, RFQ | `productId`, `variantId`, `productName`, `variantName`, `sku`, `agreedUnitPrice` (= Catalog `base_price` hiện tại; tên field giữ nguyên vì consumer đã mirror). `404` khi variant không thuộc `productId`. Trả cả variant không ACTIVE. |
| Variant snapshots batch (IN07) / Catalog | `POST /internal/v1/variants/snapshots`; Commerce (RFQ cũng được gọi) | Request `{"variantIds":[...]}`, 1–100 ID. Response theo thứ tự đầu vào, ID trùng được gộp: `variantId`, `productId`, `storeId`, `supplierId`, `productCode`, `productName`, `variantName`, `sku`, `imageUrl`, `basePrice`, `stockQuantity`, `variantStatus`, `productStatus`, `storeStatus`. Có ID không tồn tại → `404`; danh sách rỗng hoặc >100 → `400`. |
| Promotion calculation (IN08) / Catalog | `POST /internal/v1/promotions/calculate`; Commerce only | `{storeId,lines:[{variantId,quantity}]}` → `storeId`, UTC `calculatedAt`, `subtotal`, `discountTotal`, rounded `totalAmount`, `lines[]` với `appliedRules[]`, `appliedPromotions[]`. Giá và rules do Catalog nạp; không nhận arbitrary price từ caller. Quy tắc cộng dồn và làm tròn ở dưới. |
| Inventory hold / Catalog | `POST /internal/v1/inventory/reservations`; Commerce | `{reservationKey, expiresAt, lines:[{variantId,quantity}]}` → `{reservationKey,status,expiresAt,lines}`. Unique key, exact-payload replay, atomic availability check. |
| Inventory recovery / Catalog | `GET /internal/v1/inventory/reservations/{reservationKey}`; Commerce | Returns `HELD`, `EXPIRED`, `CONFIRMED` or `RELEASED`; missing key is `404`. |
| Inventory settlement / Catalog | `PUT /internal/v1/inventory/reservations/{reservationKey}/confirm` or `/release`; Commerce | Confirm deducts on-hand stock once; release frees an unconfirmed hold. Repeated same transition is idempotent; opposite transition is `409`. |
| Create order from accepted quotation / Commerce | `POST /internal/v1/orders/from-accepted-quotation`; RFQ | Accepted snapshot DTO on both sides (RFQ `CreateOrderFromAcceptedQuotationRequest`, Commerce `CreateOrderFromQuotationRequest`) → `OrderResponse(orderId,sellerOrderId,quotationId,quotationVersion,status)`. Controller currently fails closed with 501; persistence/dedup is not implemented. |
| Seller-order payment context / Commerce | `GET /internal/v1/seller-orders/{sellerOrderId}/payment-context`; RFQ/internal Commerce flow | `SellerOrderPaymentContext` |

`expiresAt` is an ISO-8601 timestamp with offset, stored and returned in UTC. Commerce chooses the agreed **15-minute** hold from checkout and must send the same key, expiry and lines on retry. A hold is unavailable after expiry even if its row still says `HELD`. `stock_quantity` is on-hand, so other stock-changing Catalog code must subtract live holds when checking availability. The checkout/payment choreography is not finalized; do not treat these endpoints alone as an end-to-end order flow.

Public RFQ accept takes only `{quotationVersion,addressId}` plus quotation ID from the URL and customer from the authenticated principal. RFQ must verify quotation ownership/version and address ownership, persist a recoverable conversion state, then construct IN09 from trusted quotation/Identity/Catalog data. IN10 lookup, durable quotation deduplication and TV5 payment obligation/stock transition are still required before enabling conversion or checkout.

Identity currently authorizes address summary for Commerce only. TV1/TV4 must agree whether RFQ receives narrowly scoped address-summary access or Commerce fetches and verifies the source address before persisting IN09; neither side may trust address fields supplied by the browser. TV5's local Java obligation API and zero-amount behavior remain TODO, so IN09 is intentionally fail-closed.

Promotion pricing applies one best BUY_X_GET_Y per reward line, then targeted/store-wide fixed discounts, then the sum of applicable percentages on the post-fixed amount (capped at 100% per line). Store-wide fixed discount is proportional to line subtotal, with the remainder on the last line. Only the seller-order `totalAmount` is rounded up to a whole VND; line amounts and `discountTotal` retain exact decimal precision. `subtotal - discountTotal` can therefore differ from `totalAmount` by the upward rounding adjustment. Checkout must not recompute promotions in Commerce or FE.
