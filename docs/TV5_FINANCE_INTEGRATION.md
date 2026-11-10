# TV5 — Commerce finance handoff

## Architecture

`Frontend payment-settlement → API-Gateway (8080) → CommerceService (8083) → Commerce DB`.
Commerce owns payment, cancellation, refund, dispute, settlement, the finance ledger,
webhook inbox and event outbox. Controllers call `FinanceService`, which calls
`FinanceRepository` for local persistence and `PaymentProvider` for payOS SDK operations.
No other service database is read. Existing Identity JWTs authorize all customer and
operator commands. The only anonymous finance route is the SDK-verified payOS callback.

Finance reads the authenticated `CommercePrincipal` from `Authentication.getPrincipal()`,
not `getDetails()`. `Actor` keeps separate `userId`, `customerId`, `supplierId` fields:
customer ownership uses the signed principal's customer profile ID; supplier ownership
uses Identity's existing `getSupplierSummaryByUser(userId)` contract and verifies the
returned user binding before accepting its supplier profile ID. That lookup happens
before finance transactions. Missing supplier profiles grant no supplier access;
Identity failures fail closed with `IDENTITY_CONTEXT_UNAVAILABLE`. Administrative
actors do not need supplier lookup. Audit, dispute opener and idempotency identity
remain tied to `userId`; profile IDs must never be inferred from matching numeric IDs.

TV3, TV5 and TV6 are modules of the **same Commerce microservice**. Their integration
uses Java service calls and local transactions; they do not make HTTP calls to themselves.
The following ports are implemented now, but calls from TV3/TV6 must be connected when
those modules are ready. There is no production mock payment or fake acceptance endpoint.

## Connect TV3

After persisting `customer_order` and `seller_order`, call the injected
`FinanceIntegrationService.syncOrder(FinanceOrderContext)` inside the order transaction:

```java
financeIntegrationService.syncOrder(new FinanceOrderContext(
    sellerOrderId, customerId, supplierId,
    totalAmount.longValueExact(), depositAmount == null ? null : depositAmount.longValueExact(),
    deliveryDate, accepted, orderSnapshotVersion));
```

The service checks the owner, supplier and total against persisted Commerce order rows.
`accepted` must represent TV3's validated acceptance, never a frontend flag. For retail,
TV3 must explicitly map the approved order-confirmation rule to this field. Source versions
start at 1 and increase per seller order. Duplicate versions with different data fail;
older versions are ignored. An unaccepted snapshot creates **no payment obligation**.

An accepted context without a configured deposit creates a FULL obligation. A fixed,
agreed deposit amount creates DEPOSIT and REMAINING obligations. A deposit must be
strictly between zero and total; a 100% deposit should be represented as FULL. This
implementation never chooses a deposit percentage. There is no buyer API that accepts
an amount or creates obligations. Amounts are exact integer VND.

REMAINING uses `total - successful collections`, requires the agreed deposit to have
been collected, and is due at **00:00 Asia/Ho_Chi_Minh on deliveryDate minus 3 days**.
Timestamps are persisted as UTC DATETIME2 and serialized with `Z`. The midnight cutoff
is explicit here and must be acknowledged when TV3 connects. If it has already passed,
initiation returns `PAYMENT_DEADLINE_PASSED`; update the agreed delivery schedule first.
Link lifetime is at most 15 minutes and never extends beyond the remaining-payment due date.

New source versions update the due date on uninitiated remaining obligations. An active
link must be reconciled and cancelled before changing delivery date. Changing the total,
deposit, ownership or reverting acceptance needs a future amendment workflow and is
rejected rather than silently changing a financial obligation.

Legacy `getPaymentContext` now reads the finance snapshot. Its deposit percentage is a
display-only derivation; the exact agreed deposit amount remains authoritative.

## Connect TV6

The approved preparation and shipping gates, cancellation recovery and smoke test are documented in [Luồng nghiệp vụ và quy tắc TV1–TV6](TV1_TV6_FLOWS_AND_RULES.md).

After persisting delivery/acceptance in the Commerce transaction, call:

```java
financeIntegrationService.syncFulfillment(sellerOrderId, fulfillmentSnapshotVersion,
    delivered, acceptedByBuyer);
```

Versions start at 1. Old events are ignored and duplicate versions must match. Snapshots
cannot undo delivery or acceptance. Acceptance without delivery is rejected. Both facts,
full collection, agreed fee configuration, and no blocking case are required for settlement.
TV5 never infers acceptance from a redirect or frontend status. Auto-acceptance is a server
job (see "QA hardening" below): a DELIVERED seller order that the buyer neither accepted
nor disputed within the 7-day dispute window is accepted by the system (`ORDER_AUTO_ACCEPTED`
audit event, actor `SYSTEM`), so settlement is not blocked forever. Reversal remains a team decision.

## payOS configuration and recovery

Copy the payOS entries from `.env.example` into backend root `.env`. The supplied private
credentials have been placed only in that gitignored file. Commerce imports this file
when run from the backend root or Commerce directory. Docker Compose passes the same
variables into Commerce; secrets are excluded from Docker build contexts. Frontend uses
only `VITE_API_BASE_URL`, pointing to Gateway.

Set the payOS channel webhook to a publicly reachable HTTPS Gateway URL ending in
`/api/payments/payos/callback`. Localhost callbacks require the team's chosen public
development tunnel. Registration with the external channel is an operator deployment
step, not performed by application startup. The exact official sample (orderCode 123,
amount 3000, sample link ID and reference) is acknowledged only after SDK signature
verification and persisted as a provider probe, without creating a payment or ledger
entry. Other unknown order codes are rejected. Our order-code sequence starts at one billion.

The adapter uses official `vn.payos:payos-java:2.0.1`. It disables SDK automatic retries
for network mutations. A unique sequence-backed orderCode and idempotency key are stored
before calling payOS, with no database lock held during the network call. Timeouts leave
the same payment in RECONCILING. Repeating initiate never sends a second create for that
key. Use reconcile to query the same orderCode. If a link was created but its checkout
URL was lost, reconcile and cancel that link before creating a replacement. A missing
provider order after an uncertain create remains for operator review; do not blindly retry.

SDK-verified callbacks compare orderCode, paymentLinkId, exact amount, VND and success.
The link ID may first be bound by a signed callback after a lost create response. A
transaction, per-order lock, unique references and inbox/ledger keys prevent double
collection from concurrent callbacks and reconciliation. Partial payments, multiple-bank-
transaction paid links, and overpayments remain in review rather than being credited
as an exact settlement. Return/cancel URLs cannot mark a payment successful.

`SUCCESS` is collection state. `HELD` is the internal ledger allocation state; it does
not claim that payOS provides escrow. Signed late payments after cancellation/expiry are
still recorded as money received, subject to the same amount and balance checks.

## Cancellation, refunds, disputes and manual payouts

Customers/suppliers request cancellation with a reason. ADMIN decides with a recorded
reason. All active links must be reconciled/cancelled before approving cancellation.
Cancellation never silently issues a refund. At most one REQUESTED/APPROVED cancellation
exists per seller order (V11 filtered unique index); a REJECTED request is kept as history
and the buyer may request again, or still use `cancel-unpaid` on an unpaid order.

Approving a cancellation of a seller order that collected money requires
`paid − confirmed (SUCCESS) refunds ≤ 0`; otherwise `409 PAID_CANCELLATION_REQUIRES_REFUND_REVIEW`.
ADMIN flow: request refund (`POST /orders/{id}/refunds`, no `disputeId`) for the collected
amount → confirm it with transfer proof → approve the cancellation. A refund still in
MANUAL_REVIEW does not count. This applies to RFQ (e.g. a refunded deposit) and to paid retail.
Approval cancels the seller order and syncs the customer order (CANCELLED once every seller
order is cancelled). An unpaid retail seller order is not approvable one by one
(`409 UNPAID_RETAIL_CANCEL_WHOLE_ORDER`): the buyer cancels the whole checkout with
`cancel-unpaid`, or the payment-timeout job does.

Retail stock restore (after the approval commits; idempotent; retried by the cancellation recovery job
`commerce.cancellation.recovery-cron` if Catalog fails):

- **Whole checkout cancelled** (every seller order CANCELLED): Commerce calls Catalog
  `PUT /internal/v1/inventory/reservations/{key}/cancel-confirmed` and stamps `customer_order.stock_restored_at`.
- **One seller order cancelled while others go on** (partial cancellation, migration V13): Commerce calls Catalog
  `PUT /internal/v1/inventory/reservations/{key}/restore-lines` with body
  `{restoreKey, lines: [{variantId, quantity}]}` — exactly that seller order's lines (`order_item` grouped by
  variant) and the restore key `{reservationKey}-so{sellerOrderId}` — then stamps `seller_order.stock_restored_at`
  and audits `STOCK_RESTORED` (detail = restore key). Until stamped, the recovery job retries with the same key.
  `STOCK_RESTORE_DEFERRED` is no longer written. Catalog answers
  `{restoreKey, reservationKey, reservationStatus, lines: [{variantId, quantity}]}`.
- **Never twice**: Catalog (Catalog migration V8, table `stock_reservation_restore`) records each restore key; a retry
  with the same key and lines returns the same result without touching stock (another payload → 409). Both
  endpoints hold the reservation row lock: `cancel-confirmed` returns only `quantity − units already restored per
  line`, and a per-line restore that arrives after the whole reservation is CANCELLED changes nothing (200,
  `reservationStatus: CANCELLED`). A per-line restore can never exceed the confirmed quantity (409), name a variant
  outside the reservation (400) or touch a reservation that is not CONFIRMED/CANCELLED (409). The endpoint is
  internal (service token `X-Service-Name: commerce`, like the other inventory endpoints).
- V13 stamps `seller_order.stock_restored_at` on every seller order already CANCELLED before it, so lines an operator
  may have added back by hand under the old rule are never restored again; if such a checkout is cancelled
  completely later, `cancel-confirmed` still returns its remaining lines as before.

Approve a cancellation of goods already SHIPPING only after the goods are back, because the stock is returned to
sale.

Until a refund matrix is agreed, ADMIN explicitly records an amount and a versioned
manual policy decision, which creates MANUAL_REVIEW. Pending and completed refunds
reserve that amount against the successful HELD payment. A refund cannot exceed what
remains available, or target a released payment. ADMIN can reject a pending proposal.
Before the manual refund, the buyer saves a bank/MoMo destination in Identity
(`PUT /api/users/me/payout-accounts/CUSTOMER`). ADMIN prepares each pending refund
with `POST /api/payments/orders/{sellerOrderId}/refunds/{refundId}/prepare-transfer`.
Commerce snapshots the beneficiary in its own DB once; later profile edits do not silently
redirect this transfer. The admin-only `GET /api/payments/orders/{sellerOrderId}/transfer-instructions`
returns the exact amount, frozen beneficiary, `MSSREFUND{id}` memo and VietQR image link.
Legacy pending refunds must also be prepared before confirmation. If Identity has no account,
the preparation returns `RECIPIENT_ACCOUNT_MISSING` and the refund remains pending.
Reconcile the stable memo before retrying any bank transfer with an uncertain outcome.
Only successful-transfer reference plus HTTPS evidence can confirm a refund; there is
no automatic 100% calculation and no fake bank transfer. Ledger and audit retain both
the policy snapshot and outcome. Pending refunds block settlement. Confirmed refunds
reduce the settlement base; `gross_amount = refunded_amount + platform_fee + supplier_payout`.

Only the buyer opens a dispute after a successful payment. Before delivery it requires
an overdue promised date; after delivery it must be opened within seven days of the
last recorded delivery. The promised date is the latest expected delivery of the non-cancelled
shipments, else the agreed RFQ delivery date + 1 day. Without an agreed delivery date (retail),
the shipping SLA also applies: once the order is fully paid, the buyer may open a non-delivery
dispute `commerce.retail.shipping-sla-days` (default 7) days after the last collection, and a
later supplier estimate cannot postpone this. While a dispute is OPEN, ADMIN_REVIEW or
REFUND_PENDING, the supplier cannot create/ship shipments and the buyer cannot accept the order.
A seller may respond once. The buyer or ADMIN may escalate after that
response or after 48 hours without one. Only ADMIN decides. `REJECT` closes the case;
`REFUND` records the awarded amount and keeps the case open until linked manual refunds
are confirmed with proof. An open case blocks settlement. The 48-hour deadline does not
automatically decide the case. The admin queue is `/api/admin/commerce/disputes`.

Settlement fee rate is snapshotted at 5% on collected money remaining after confirmed
refunds (`NET_AFTER_REFUND`, `HALF_UP` by default). Original gross and confirmed refunds
are snapshotted separately. Full refunds have no payout. Settlement requires buyer
acceptance or a recorded ADMIN decision on a post-delivery dispute, no blocking case,
and seven days since the last recorded delivery.

The current payout workflow is explicitly **manual**, with no payOS payout webhook or
automated transfer call. A supplier saves its own destination with
`PUT /api/users/me/payout-accounts/SUPPLIER` (Seller Center > Doanh thu & giải ngân):

1. Create READY settlement after checking eligibility.
2. Begin payout rechecks all blockers under the same per-order lock, snapshots the supplier
   destination from Identity and creates `MSSPAYOUT{id}`. State becomes PROCESSING, funds remain
   HELD. Without an account, begin fails without changing the settlement. For legacy PROCESSING
   rows lacking a snapshot, use `POST /api/payments/orders/{sellerOrderId}/settlements/{settlementId}/prepare-transfer`.
3. The authorized operator reads the admin-only transfer instruction, checks the account holder,
   account number, amount and memo in the bank app, transfers once, and reconciles that memo first
   if the result is uncertain. A second payout request never creates a new transfer.
4. Confirm requires the successful bank reference and HTTPS proof. Only then does the
   settlement become SUCCESS and payment allocation become RELEASED. The fee and payout
   ledger entries plus confirmed refunds sum to the collected gross snapshot.

The QR is a VietQR Quick Link, which prefills a transfer and sends the destination account,
amount and memo to VietQR's image service. It is **not** proof of payment and does not verify
the typed account holder. ADMIN must verify the name shown by the bank app and supply real
bank evidence. Accounts are stored in Identity; transfer snapshots belong only to Commerce.

A dispute or refund submitted before transfer moves an existing READY settlement to
ON_HOLD. After the case and refunds finish, ADMIN runs settlement creation again to
recalculate the same settlement row; the old payout amount cannot be used. Once payout
is PROCESSING or SUCCESS, complaint/refund mutations return `PAYOUT_ALREADY_RESERVED`.
Reconcile any in-flight transfer before changing finance state.
If transfers need automatic retries, add a provider payout adapter and outcome queries
before enabling them. Payout webhook signatures must use the payout-specific SDK rules,
not the collection verifier. No failed/unknown manual transfer is treated as successful.
After payout, support must handle exceptional complaints manually; the finance API
cannot reverse a confirmed bank transfer.

## API implemented now

All routes below use Gateway `/api/payments`. Commands marked K require an
`Idempotency-Key` of 8–100 ASCII letters/digits/colon/underscore/hyphen. Reusing a key
with another command/body is rejected. Ownership and operator rights are checked server side.

| Method / suffix | Audience | Purpose |
|---|---|---|
| GET `/orders/{orderId}` | Owner, supplier, ADMIN | Context, obligations, cases, eligibility, latest 200 audit entries |
| GET `/settlements/mine?page=&size=` | Supplier | Own seller orders' settlement state + pending/ready/paid totals (see "Seller payout view") |
| POST `/{paymentId}/initiate` K | Buyer | Initiate server-owned obligation; cancelled/expired attempts can be replaced |
| POST `/{paymentId}/reconcile` | Parties, ADMIN | Query payOS; no frontend success flag |
| POST `/{paymentId}/cancel-link` | Buyer | Cancel unpaid provider link, not collected funds |
| POST `/payos/callback` | payOS signature | Collection webhook |
| POST `/orders/{orderId}/cancellations` K | Parties | `{reason}` |
| POST `/orders/{orderId}/cancellations/{id}/decision` K | ADMIN | `{approve,reason}` |
| POST `/orders/{orderId}/disputes` K | Buyer | `{reason,evidenceUrl?}` |
| POST `/orders/{orderId}/disputes/{id}/evidence` K | Parties | `{evidenceUrl}` |
| POST `/orders/{orderId}/disputes/{id}/respond` K | Supplier | `{reason}` as response |
| POST `/orders/{orderId}/disputes/{id}/escalate` K | Buyer/ADMIN | No body; seller responded or 48 hours elapsed |
| POST `/orders/{orderId}/disputes/{id}/decision` K | ADMIN | `{decision: REJECT/REFUND,amount?,reason}` |
| POST `/orders/{orderId}/refunds` K | ADMIN | `{paymentId,amount,reason,policySnapshot,disputeId?}` |
| POST `/orders/{orderId}/refunds/{id}/reject` K | ADMIN | `{reason}` |
| POST `/orders/{orderId}/refunds/{id}/confirm` K | ADMIN | `{reference,evidenceUrl}` |
| POST `/orders/{orderId}/settlements` K | ADMIN | Snapshot eligible settlement |
| POST `/orders/{orderId}/settlements/{id}/payout` K | ADMIN | Reserve one manual payout |
| POST `/orders/{orderId}/settlements/{id}/confirm` K | ADMIN | `{reference,evidenceUrl}` |

Suppliers receive no checkout URL or QR credential in the aggregate response. Error
responses contain stable `code`, never raw SDK messages, SQL, secrets or bank payloads.
OpenAPI schemas are generated from the controllers at Commerce `/v3/api-docs`.

`POST /{paymentId}/initiate` on a payment of a RETAIL order returns
`409 RETAIL_CUSTOMER_ORDER_PAYMENT_REQUIRED`: retail is paid once for the whole checkout through
`POST /customer-orders/{orderId}/initiate`. Per-seller-order links are for RFQ only.
`GET /orders/{id}` for an unknown seller order returns `404 ORDER_NOT_FOUND`.
`cancellations` in the aggregate may hold several entries (ordered oldest first): REJECTED history
followed by the current case.

## QA hardening (08/11/2026)

Rules added after the QA run on the shared stack (all in CommerceService; migration V11):

| Rule | Behaviour | Error / event |
|---|---|---|
| Paid cancellation | Approve once confirmed refunds cover the collection (RFQ and retail); retail restores the whole checkout stock when every seller order is cancelled, otherwise exactly the cancelled seller order's lines (per-line restore, see "Retail stock restore") | `PAID_CANCELLATION_REQUIRES_REFUND_REVIEW`, `UNPAID_RETAIL_CANCEL_WHOLE_ORDER`, audit `STOCK_RESTORED` |
| Re-request after rejection | New request allowed after REJECTED; unpaid cancel and timeout jobs ignore rejected cases | `CANCELLATION_ALREADY_EXISTS` only while a REQUESTED/APPROVED case exists |
| Fulfillment gate | Preparation/shipping uses `paid − confirmed refunds`; blocked by any non-rejected cancellation, MANUAL_REVIEW refund, or OPEN/ADMIN_REVIEW/REFUND_PENDING dispute | 400 `Insufficient payment for fulfillment`, 409 `Fulfillment blocked by financial case` |
| Buyer acceptance | Blocked while a dispute is unresolved | 409 |
| Retail payment timeout | Unpaid retail checkout (AWAITING_PAYMENT) older than `commerce.retail.payment-timeout-minutes` (default 60, cron `commerce.retail.payment-timeout-cron`, default every minute) is cancelled by `SYSTEM`: open links are cancelled first (a payment made at the deadline is collected and the order proceeds), then stock is restored | cancellation reason `Quá hạn thanh toán 60 phút` |
| RFQ deposit timeout | Unchanged, except an order with a REJECTED cancellation is now also auto-cancelled; only APPROVED cases exclude it | |
| Retail per-seller payment | Rejected | `RETAIL_CUSTOMER_ORDER_PAYMENT_REQUIRED` |
| Shipping SLA | Non-delivery dispute allowed `commerce.retail.shipping-sla-days` (default 7) after full payment when no delivery date was agreed | `DELIVERY_NOT_OVERDUE` before that |
| Auto-acceptance | DELIVERED, not accepted, last delivery ≥ 7 days ago, no unresolved dispute and none opened since delivery → ACCEPTED by `SYSTEM` (cron `commerce.fulfillment.auto-accept-cron`, default every 10 minutes) | audit `ORDER_AUTO_ACCEPTED` |
| Stock restore recovery | Failed Catalog restores (whole checkout or one seller order's lines) retried by `commerce.cancellation.recovery-cron` (existing, default every 30 s) | |

Other Commerce endpoints touched by the same run: `POST /api/shipments/{id}/cancel` (supplier
withdraws a PLANNED shipment; status `CANCELLED`, which no longer blocks the order from becoming
DELIVERED and is ignored as a delivery promise), `expectedDeliveryAt ≥ expectedShipAt` on shipment
create/update (400), `deliveryAddress` + `discountTotal` on `SellerOrderResponse`, ADMIN read of
`GET /api/seller-orders/{id}`, cart stock validation (`409 INSUFFICIENT_STOCK`), unavailable cart
lines in `unavailableItems` instead of a 409 for the whole cart (checkout then returns
`409 CART_HAS_UNAVAILABLE_ITEMS`), self-purchase guard (`409 SELF_PURCHASE_NOT_ALLOWED`), and
downstream/request errors as `{status, code, message, timestamp}` with a Vietnamese message and
no internal URL: `INSUFFICIENT_STOCK`/`PRODUCT_UNAVAILABLE`/`DOWNSTREAM_CONFLICT` (409),
`ADDRESS_NOT_FOUND`/`PRODUCT_NOT_FOUND`/`STORE_NOT_FOUND`/`ACCOUNT_NOT_FOUND` (404),
`INVALID_REQUEST` (400), `DOWNSTREAM_UNAVAILABLE` (503), `IDEMPOTENCY_KEY_REQUIRED`,
`MISSING_HEADER`, `MISSING_PARAMETER`, `MALFORMED_REQUEST`, `INVALID_PARAMETER` (400),
`INTERNAL_ERROR` (500, no exception text). Finance routes keep `{code,message}` and
`INVALID_FINANCE_REQUEST` for invalid bodies.

## Shipment lines (split a seller order into several shipments by item, migration V12)

A shipment carries `lines` `{orderItemId, quantity}` of its own seller order (table `shipment_line`).

- `POST /api/shipments` accepts optional `lines: [{orderItemId, quantity}]`. Rules: quantity > 0
  (`400 SHIPMENT_LINE_INVALID_QUANTITY`), each item belongs to that seller order (`400 SHIPMENT_LINE_ITEM_NOT_IN_ORDER`),
  at most once per shipment (`400 SHIPMENT_LINE_DUPLICATE_ITEM`), and the units per item across non-cancelled
  shipments never exceed the ordered quantity (`409 SHIPMENT_QUANTITY_EXCEEDS_REMAINING`). Errors are
  `{status, code, message, timestamp}` with a Vietnamese message.
- **Backward compatible**: without `lines` (or with `[]`) the shipment takes every unit not yet in a non-cancelled
  shipment; when nothing remains → `409 NOTHING_LEFT_TO_SHIP`. A cancelled (withdrawn PLANNED) shipment gives its
  units back. Lines cannot be edited: cancel the planned shipment and create another.
- The seller order becomes DELIVERED only when every ordered unit is in DELIVERED shipments. While a non-cancelled
  **legacy** shipment without lines exists (created before V12, content unknown), the old rule applies: every
  non-cancelled shipment is delivered.
- `ShipmentResponse.lines`: `[{orderItemId, productNameSnapshot, variantNameSnapshot, skuSnapshot, quantity}]`
  (empty for a legacy shipment).
- `GET /api/shipments/items?sellerOrderId=` (buyer, supplier, ADMIN — same access as the shipment list): per order
  item `{orderItemId, productNameSnapshot, variantNameSnapshot, skuSnapshot, orderedQuantity, allocatedQuantity,
  shippedQuantity, deliveredQuantity, remainingQuantity}` — allocated = in non-cancelled shipments, shipped = in
  SHIPPED/DELIVERED, delivered = in DELIVERED, remaining = ordered − allocated. Legacy shipments are not counted.

## Seller payout view (Doanh thu & giải ngân)

`GET /api/payments/settlements/mine?page=0&size=20` (verified supplier only; others `403 SUPPLIER_REQUIRED`;
page < 0 or size outside 1–100 → `400 INVALID_FINANCE_REQUEST`). Read-only, takes no finance lock. Returns every
seller order of the caller's supplier profile that has a finance context, newest first:

`{content: [Row], page, size, totalElements, totalPages, totals: {pendingAmount, readyAmount, paidAmount}}`

Row: `sellerOrderId, sellerOrderNo, orderId, orderNo, storeName, orderStatus, placedAt, totalAmount,
grossCollected` (successful collections), `refundedAmount` (confirmed refunds), `platformFee, netPayout,
feeEstimated, eligible, blockingReasons` (same codes as the order summary), `settlementId, settlementStatus,
payoutStatus, payoutReference, paidAt`.

- A READY/PROCESSING/SUCCESS settlement shows its snapshot (`feeEstimated: false`). Otherwise (none yet, or ON_HOLD
  which must be recalculated) fee and payout are estimated with the same fee function as `createSettlement`
  (5%, `finance.settlement.rounding`) on `grossCollected − refundedAmount`; null while the rounding rule is not one
  of HALF_UP/DOWN/UP.
- `payoutStatus`: `PAID` (settlement SUCCESS, `paidAt` = `settled_at`), `PROCESSING` (manual transfer reserved),
  `READY` (settlement READY, or eligible without a settlement yet), `ON_HOLD`, `BLOCKED` (not eligible yet, see
  `blockingReasons`), `NO_PAYOUT` (cancelled seller order or fully refunded).
- Totals over all of the supplier's seller orders (not only the page): pending = BLOCKED + ON_HOLD, ready = READY +
  PROCESSING, paid = PAID (sums of `netPayout`). Cost is a few queries per seller order; add a summary table or cache
  if a supplier accumulates thousands of orders.

## In-app notifications (Identity, 08/11/2026)

`notification/CommerceNotifier` posts to Identity `POST /internal/v1/notifications` through the existing
`IdentityServiceClient` (`X-Service-Name: commerce`, `X-Service-Token` = `COMMERCE_TO_IDENTITY_TOKEN`). Same rules as
RFQService's `RfqNotifier`:

- Recipients are the `customer_id` / `supplier_id` of the seller order's `finance_context` (Identity resolves the
  account); order numbers are `customer_order.order_no` (buyer) and `seller_order.seller_order_no` (supplier). No
  extra cross-service lookup.
- Requests are built inside the business transaction and sent only after it commits (`TransactionSynchronization`);
  without a transaction they are sent at once. A rolled-back action notifies nobody.
- Best effort, no retry: a lookup or Identity failure is logged (`warn`) and never fails payment, shipment,
  cancellation, dispute or payout. A lost notification is not replayed.
- Targets: buyer `{"kind":"ORDER","id":"<customerOrderId>"}`; supplier
  `{"kind":"ORDER","id":"<sellerOrderId>","role":"SELLER"}`. Titles/bodies are Vietnamese, amounts as `10.001 ₫`.

| Event (where) | Recipient | type | dedupKey |
|---|---|---|---|
| Seller-order payment collected — webhook or reconcile (`FinanceServiceImpl.collect`) | supplier | `PAYMENT_RECEIVED` (title by DEPOSIT / REMAINING / FULL) | `PAYMENT_RECEIVED:{paymentId}` |
| same | buyer | `PAYMENT_CONFIRMED` | `PAYMENT_CONFIRMED:{paymentId}` |
| Retail checkout payment collected (`CustomerPaymentServiceImpl.collect`) | each supplier | `PAYMENT_RECEIVED` | `PAYMENT_RECEIVED:{paymentId}` (its seller-order payment) |
| same | buyer (once) | `PAYMENT_CONFIRMED` | `PAYMENT_CONFIRMED:CUSTOMER_PAYMENT:{customerPaymentId}` |
| Shipment shipped (`markAsShipped`) | buyer | `SHIPMENT_SHIPPED` | `SHIPMENT_SHIPPED:{shipmentId}` |
| Shipment delivered (`markAsDelivered`) | buyer | `SHIPMENT_DELIVERED` | `SHIPMENT_DELIVERED:{shipmentId}` |
| Seller order became DELIVERED (same call) — reminder to accept within 7 days | buyer | `ORDER_DELIVERED` | `ORDER_DELIVERED:{sellerOrderId}` |
| Buyer acceptance (`acceptSellerOrder`) / auto-accept job (`autoAcceptDelivered`) | supplier | `ORDER_ACCEPTED` | `ORDER_ACCEPTED:{sellerOrderId}` |
| Cancellation requested (`requestCancellation`; not when the supplier itself asked) | supplier | `CANCELLATION_REQUESTED` | `CANCELLATION_REQUESTED:{cancellationId}` |
| Cancellation approved (`decideCancellation`) | buyer | `ORDER_CANCELLED` | `ORDER_CANCELLED:{orderId}` (multi-store checkout: `ORDER_CANCELLED:{orderId}:{sellerOrderId}`) |
| same | supplier | `ORDER_CANCELLED` | `SELLER_ORDER_CANCELLED:{sellerOrderId}` |
| Cancellation rejected (`decideCancellation`) | buyer | `CANCELLATION_REJECTED` | `CANCELLATION_REJECTED:{cancellationId}` |
| Unpaid RFQ/retail order cancelled by the timeout jobs (`cancelUnpaidOrder`, system), with the reason | buyer | `ORDER_CANCELLED` | `ORDER_CANCELLED:{orderId}` |
| Dispute opened (`openDispute`) | supplier | `DISPUTE_OPENED` | `DISPUTE_OPENED:{disputeId}` |
| Dispute decided, REJECT or REFUND award (`decideDispute`) | buyer + supplier | `DISPUTE_RESOLVED` | `DISPUTE_RESOLVED:{disputeId}:CUSTOMER` / `:SUPPLIER` |
| Refund confirmed with transfer proof (`confirmRefund`) | buyer | `REFUND_CONFIRMED` | `REFUND_CONFIRMED:{refundId}` |
| Payout confirmed paid (`confirmPayout`) | supplier | `PAYOUT_PAID` | `PAYOUT_PAID:{settlementId}` |

The cancellation keys are the ones RFQService's order sync uses, so an RFQ order (one seller order per customer
order) cancelled in Commerce is announced once, by whichever service posts first. Idempotent replays of finance
commands and duplicate webhooks run no business action and therefore send nothing; Identity's dedup covers the rest.

Not notified (yet): a buyer cancelling their own unpaid order (the supplier of a retail order is not told; RFQService
tells the supplier of an RFQ order), the recovery job finishing an interrupted unpaid cancellation, dispute seller
response/escalation, refund rejection, settlement creation and payout start. A payment collected after its order
was cancelled is still reported as received (the money is real and goes to manual review).

## Events and frontend integration still to connect

Every mutation writes audit and outbox in the same transaction. Implement the
`FinanceEventTransport` bean against the team's chosen broker/transport; the scheduled
dispatcher retains events until a transport exists and durably accepts them. Events carry
eventId, sellerOrderId, aggregateVersion, type and resourceId. Delivery is at least once;
consumers deduplicate eventId, ignore older aggregate versions, and retrieve a complete
snapshot to avoid losing updates from other finance subresources. There is no existing
broker configured in this repo, so events currently remain durably queued.

Frontend feature: `src/features/payment-settlement/`. Its real Gateway client does not use
the existing order mock. TV1 must call shared `setAccessToken(jwt)` on login/refresh and
`setAccessToken(null)` on logout. Tokens are kept in memory. There is deliberately no JWT
input or secret in the finance page. TV3 can navigate to the page with the actual numeric
sellerOrderId; for now users can load that ID. The return URL opens this feature and asks
for an authoritative status check. Hosted payOS checkout displays the QR.

The page includes pending/success/error/expired/reconciling states, payment links,
cancellation and dispute requests, operator commands, settlement eligibility and audit.
The existing TV1/TV3 order/login mocks remain unchanged until their owners connect them.

## Decisions required before activation

- Fixed deposit amount/percentage and how retail confirmation maps to acceptance (TV3).
- Midnight remaining-payment cutoff acknowledgement; amendment/overdue schedule workflow.
- Cancellation-specific refund matrix and automated refund approval authority.
- Post-payout exceptional complaint handling and proof upload storage.
- Exact TV6 receipt/acceptance conditions beyond the seven-day delivery hold.
- payOS Payouts entitlement, bank destination verification and automatic payout adapter.
- Broker transport, consumer bindings, public webhook registration/tunnel and TV1 JWT hook.

## Verification

`mvn -pl CommerceService,API-Gateway -am test` runs service tests, concurrency tests and
real SDK signature tests without contacting payOS or moving money. H2 tests execute V1, V3,
V7, V8, V10, V11, V12 and V13 with documented syntax adaptations (H2 has no filtered index, so the one-open-
cancellation rule is enforced by the service there); they do not substitute for SQL Server validation.
`scripts/tv5-sqlserver-check.ps1` applies the original migrations to a uniquely named local
SQL Server test database, checks filtered uniqueness and integer-VND constraints, then
drops only that database. It uses Windows authentication and leaves production DBs alone.

Frontend: `npm run lint` and `npm run build`. An end-to-end payment with a real bank is
still dependent on TV1/TV3/TV6 integration and public channel webhook configuration.

Official SDK reference: https://github.com/payOSHQ/payos-lib-java
Collection API reference: https://payos.vn/docs/api/
