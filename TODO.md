# MSS Mooncake Marketplace Workplan

TODO này bám theo hai tài liệu project. 




## TV1 — Account, Supplier Onboarding, Store

**Service:** Identity + Catalog & Store  
**Entity:** Identity: `APP_USER`, `CUSTOMER_PROFILE`, `SUPPLIER_PROFILE`, `ADDRESS`; Catalog: `STORE`

- Backend: register/login/profile/address; supplier onboarding; store CRUD, branding/theme.
- Frontend: `account/`, `supplier-store/`; profile/address và supplier/store screens.
- Database/migration: Identity migration cho user/profile/address; Catalog migration cho supplier/store; không dùng chung migration.
- API: `/api/auth/register`, `/api/users/me`, `/api/addresses`, `/api/supplier-profile`, `/api/stores`.
- Test: validation, ownership address/store, onboarding happy path, contract tests.
- Phối hợp: publish `UserSummary`, `AddressSummary`, `SupplierSummary`, `StoreSummary` cho TV2/TV3/TV4.
- Backend hiện lưu reason/audit duyệt supplier ở Identity V2; cần nhóm nghiệp vụ chốt tiêu chí duyệt và cách bootstrap admin ngoài allowlist MVP. Auth dùng HS256, admin ID allowlist và các status flow được mô tả trong `TODO_IMPLEMENTATION.md`.

## TV2 — Catalog, Product, Promotion

**Service:** Catalog & Store  
**Entity:** `CATEGORY`, `PRODUCT`, `PRODUCT_VARIANT`, `PROMOTION`, `PROMOTION_RULE`

- Backend: CRUD catalog, storefront/search; `PERCENT_DISCOUNT`, `FIXED_DISCOUNT`, `BUY_X_GET_Y`.
- Frontend: `catalog-promotion/`; catalog management, storefront/search, promotion editor.
- Database/migration: category/product/variant/promotion/rule và index sau khi chốt DB.
- API: `/api/categories`, `/api/products`, `/api/search`, `/api/promotions`.
- Test: ba promotion types, trigger/reward item khác nhau, expiry, snapshot sau catalog update.
- Phối hợp: publish `ProductSnapshot/VariantSnapshot`, `PromotionCalculationResult`; chốt với TV1/TV3/TV4.
- TODO: search technology, currency/rounding và precedence nhiều promotion chưa chốt.

## TV3 — Cart, Checkout, Order

**Service:** Commerce  
**Entity:** `CART`, `CART_ITEM`, `CUSTOMER_ORDER`, `SELLER_ORDER`, `ORDER_ITEM`

- Backend: cart; checkout; split multi-store; immutable product/store/address/quotation snapshots; order history.
- Frontend: `cart-order/`; cart, multi-store checkout, order history.
- Database/migration: cart/order aggregates, snapshot columns và idempotency constraints.
- API: `/api/cart/items`, `/api/checkout`, `/api/orders`; internal `POST /internal/v1/orders/from-accepted-quotation`.
- Test: >=2 store tạo đúng 1 customer order + seller orders; quotation conversion không recalculate; idempotency.
- Phối hợp: consume TV1 user/address, TV2 product/promotion; publish `SellerOrderPaymentContext`; chốt TV4.
- TODO: transaction boundary và failure compensation khi split seller orders.

## TV4 — Public RFQ, Quotation, Chat

**Service:** RFQ & Negotiation  
**Entity:** `RFQ`, `QUOTATION`, `QUOTATION_ITEM`, `CONVERSATION`, `CONVERSATION_MESSAGE`

- Backend: public RFQ; supplier submit/revise quotation; version/compare/accept; conversation/message persistence; WebSocket/STOMP.
- Frontend: `rfq-negotiation/`; feed/create, quote/revise, compare, chat.
- Database/migration: bám `MSS_Physical_Data_Model_SQLServer_v1.md`; RFQ version/expiry/terms và `CONVERSATION_MESSAGE`; không tạo `RFQ_ITEM`/`RFQ_RECIPIENT`.
- API: `/api/rfqs`, `/api/rfqs/{id}/quotations`, `/api/quotations/{id}/accept`, `/ws/chat`.
- Test: multiple quotations, version revision, accepted immutable, expiry, accept idempotency, persist message trước broadcast.
- Phối hợp: consume TV1/TV2 summaries/snapshots; call TV3 order creation; handoff TV5.
- TODO: accept nhiều quotation trong một RFQ, chat authorization và message guarantee.

## TV5 — Payment, Cancel/Refund, Dispute, Settlement

**Service:** Commerce  
**Entity:** `PAYMENT`, `CANCELLATION`, `REFUND`, `DISPUTE`, `SETTLEMENT`

- Backend: payment adapter/webhook; `FULL`/`DEPOSIT`/`REMAINING`; SUCCESS -> HELD, settlement -> RELEASE; refund policy; dispute blocking; fee 5% snapshot/payout.
- Frontend: `payment-settlement/`; payment, cancel/refund, dispute, settlement views.
- Database/migration: finance tables, state transitions, provider/idempotency keys, fee snapshot.
- API: payment obligation/callback/history; cancellation/refund; dispute; settlement eligibility/status.
- Test: no deposit before ACCEPT; webhook idempotent; remaining due = delivery - 3 days; refund not default 100%; dispute blocks settlement; payout once.
- Phối hợp: consume TV3 payment context and TV6 delivered/accepted; publish payment/dispute/settlement status.
- TODO: provider, cancellation/refund matrix, dispute authority/SLAs chưa được chốt.

## TV6 — Fulfillment, Shipment, Acceptance, Review

**Service:** Commerce  
**Entity:** `SHIPMENT`, `REVIEW`

- Backend: đã hoàn thiện đầy đủ DTOs, Exception handling, Controller (`ShipmentController`, `OrderAcceptanceController`, `ReviewController`), Service (`FulfillmentApplicationServiceImpl`), và Repositories.
- Frontend: đã hoàn thiện đầy đủ thư mục `src/features/fulfillment/` (`fulfillmentApi.js`, `ShipmentTracker`, `OrderAcceptanceCard`, `ReviewModal`, `ReviewList`, `SupplierShipmentManager`, `FulfillmentDemoPage`).
- Database/migration: sử dụng physical schema V1 (`shipment`, `review`).
- API: hoàn thiện đầy đủ `POST /api/shipments`, `GET /api/shipments`, `PATCH /api/shipments/{id}`, `POST /api/shipments/{id}/ship`, `POST /api/shipments/{id}/delivered`, `POST /api/seller-orders/{sellerOrderId}/accept`, `POST /api/reviews`, `GET /api/reviews`.
- Test: đã viết bộ kiểm thử tự động toàn diện `FulfillmentApplicationServiceImplTest` bao gồm happy paths và các validation/edge cases (ForbiddenException, BadRequestException, ConflictException).
- Phối hợp: cung cấp phương thức nội bộ `isEligibleForSettlement(Long sellerOrderId)` cho TV5 giải ngân tiền sau khi khách nghiệm thu. Không dùng Feign tới TV3/TV5 vì cùng Commerce Service.

## Contract register

| Contract | Provider | Consumer | Giao tiếp |
|---|---|---|---|
| `UserSummary`, `AddressSummary` | TV1 / Identity | TV3, TV4 | OpenFeign GET |
| `StoreSummary`, `SupplierSummary` | TV1 / Catalog & Store | TV2, TV3, TV4 | OpenFeign GET |
| `ProductSnapshot`, `VariantSnapshot` | TV2 / Catalog & Store | TV3, TV4 | OpenFeign GET |
| `PromotionCalculationResult` | TV2 / Catalog & Store | TV3 | OpenFeign query/command |
| `CreateOrderFromAcceptedQuotation` | TV3 / Commerce | TV4 | OpenFeign POST, idempotent |
| `QuotationAccepted` | TV4 / RFQ | TV3, TV5 | DTO handoff; boundary TODO |
| `SellerOrderPaymentContext` | TV3 / Commerce | TV5 | Internal application service |
| `DepositPaid`, `RemainingPaid` | TV5 / Commerce | TV3, TV6 | Internal application service |
| `ShipmentDelivered`, `OrderAccepted` | TV6 / Commerce | TV3, TV5 | Internal application service |
| `DisputeBlockingStatus`, `SettlementPaid` | TV5 / Commerce | TV3, TV6 | Internal application service |

## Definition of Done

- API/OpenAPI contract được provider và consumer review.
- Migration đúng service database owner.
- Logic ở `service`/`service/impl`; REST chỉ dùng DTO.
- Có unit policy test và integration/contract test cho happy/failure path.
- Frontend happy path đi qua Gateway.
- Command quan trọng có idempotency; không retry mù.
- PR ghi rõ snapshot, status transition và TODO còn mở.
