# Internal API contracts — v1

This file is the source of truth for the internal summaries implemented in the current backend. Each endpoint is owned by the service listed below. Consumer DTOs live in the consuming service and mirror the provider JSON; services do not share JPA entities or implementation classes.

## Summary endpoints

| Contract / owner | Endpoint and consumers | Response fields (JSON names) | Not found |
|---|---|---|---|
| User summary / Identity | `GET /api/user/{userId}/summary`; Commerce, RFQ | `userId` (Identity `APP_USER.user_id`), `customerId` (nullable), `fullName` (nullable), `email`, `phone` (nullable) | `404` when `userId` does not exist. A user without a customer profile still returns `200`; profile fields are `null`. |
| Address summary / Identity | `GET /api/address/{id}/summary`; Commerce, RFQ | `addressId`, `customerId`, `recipientName`, `recipientPhone`, `addressLine`, `ward` (nullable), `district` (nullable), `city` | `404` when the address ID does not exist. Callers verify buyer ownership before persisting address snapshots. |
| Supplier summary / Identity | `GET /api/supplier/{id}/summary` or `/by-user/{userId}/summary`; Catalog, Commerce, RFQ | `supplierId`, `userId`, `companyName`, `taxCode`, `verificationStatus` | `404` when the supplier ID or user ID has no supplier profile. |
| Store summary / Catalog & Store | `GET /internal/v1/stores/{id}/summary`; Commerce, RFQ | `storeId`, `supplierIdRef` (Identity supplier ID), `storeCode`, `name`, `slug`, `logoUrl` (nullable), `status` | `404` when the store ID does not exist. This internal summary can include non-public statuses. |

Identity lookup endpoints use `/api/user`, `/api/address` and `/api/supplier` and remain service-only: they are hidden from Swagger, omitted from Gateway routes, and require service tokens. Other internal service contracts use `/internal/v1`. For versioned contracts, the version belongs in the URI; there is no `version` property in the JSON body. JSON property names and ID ownership above are part of the contract.

## Internal authentication and errors

Callers send both `X-Service-Name` and `X-Service-Token`. The name must match the caller registered by the receiving service, and the token must be the dedicated secret for that caller/provider pair. Names are `catalog`, `commerce`, and `rfq`. Current endpoint permissions are:

| Identity endpoint | Accepted callers |
|---|---|
| User summary | `commerce`, `rfq` |
| Address summary | `commerce` |
| Supplier summary | `catalog`, `commerce`, `rfq` |
| Catalog store/product summaries | `commerce`, `rfq` |
| Commerce internal order/payment-context endpoints | `rfq` |

Clients should branch on the HTTP status, not parse an error message. A missing summary is always HTTP `404`; Identity returns its `ErrorResponse` (`status`, `error`, `message`, `path`, optional `details`), while Catalog returns its service error body (`status`, `message`, `timestamp`). Invalid internal credentials do not grant the endpoint authority.

## Public routing through Gateway

The Gateway owns external HTTP routing and CORS. Identity routes include auth, users, addresses, admin operations, and a dedicated `/api/supplier-profile/**` route to Identity. Store/catalog paths route to Catalog; order/payment paths to Commerce; RFQ/quotation/chat paths to RFQ. `/internal/**` is not exposed as a Gateway route.

Browser development can call Gateway from `http://localhost:5173`. Configure `CORS_ALLOWED_ORIGINS` as a comma-separated list for other explicit FE origins. The Gateway allows credentialed requests with the supported API methods and headers. This repository has no FE code or Vite configuration; when the FE is added, it should use the Gateway base URL and keep its own Vite proxy in the FE repository if preferred.

## Other internal contracts

| Contract / owner | Endpoint / consumer | Current versioned payload |
|---|---|---|
| Product variant snapshot / Catalog | `GET /internal/v1/products/{productId}/variants/{variantId}/snapshot`; Commerce, RFQ | `productId`, `variantId`, `productName`, `variantName`, `sku`, `agreedUnitPrice` |
| Create order from accepted quotation / Commerce | `POST /internal/v1/orders/from-accepted-quotation`; RFQ | `CreateOrderFromQuotationRequest` → `OrderResponse` |
| Seller-order payment context / Commerce | `GET /internal/v1/seller-orders/{sellerOrderId}/payment-context`; RFQ/internal Commerce flow | `SellerOrderPaymentContext` |
