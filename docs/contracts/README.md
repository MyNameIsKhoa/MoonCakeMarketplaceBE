# Cross Service Contract Inventory

Inventory khởi đầu cho contract giữa 4 service. Record Java nằm trong consumer/provider để không share implementation giữa module.

| Contract | Provider | Consumer | Current skeleton |
|---|---|---|---|
| User summary | Identity | Commerce, RFQ | `GET /internal/v1/users/{userId}/summary`; response carries `userId`, `customerId`, profile fields |
| Product snapshot | Catalog & Store | Commerce, RFQ | `GET /internal/v1/products/{productId}/variants/{variantId}/snapshot` |
| Create order from accepted quotation | Commerce | RFQ | `POST /internal/v1/orders/from-accepted-quotation` |
| Seller-order payment context | Commerce | Commerce finance flow | `GET /internal/v1/seller-orders/{sellerOrderId}/payment-context` |

TODO: bổ sung Store/Supplier summary, Address summary, PromotionCalculationResult, QuotationAccepted và status payload sau khi TV1-TV6 chốt field-level contract. DTO skeleton hiện tại chưa phải public API ổn định.
