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
The exact TV6 meaning of buyer acceptance, SLA, reversal and auto-acceptance remains a
team decision; TV5 does not infer it from a redirect, clock or frontend status.

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
Cancellation never silently issues a refund. One cancellation per order follows the
existing V1 unique constraint.

Until a refund matrix is agreed, ADMIN explicitly records an amount and a versioned
manual policy decision, which creates MANUAL_REVIEW. Pending and completed refunds
reserve that amount against the successful HELD payment. A refund cannot exceed what
remains available, or target a released payment. ADMIN can reject a pending proposal.
Use stable reference `mss-refund-{refundId}` for the manual transfer and reconcile that
reference before retrying any bank transfer with an uncertain outcome.
Only successful-transfer reference plus HTTPS evidence can confirm a refund; there is
no automatic 100% calculation and no fake bank transfer. Ledger and audit retain both
the policy snapshot and outcome. Successful or pending refunds conservatively block
settlement until a future partial-refund settlement policy is approved.

Both parties can open a dispute and append evidence to immutable history. An open
dispute blocks settlement and payout. ADMIN resolution is **disabled by default**;
enable `FINANCE_DISPUTE_ADMIN_ENABLED` only after the authority is agreed. SLA remains
unconfigured; there is no automatic decision on expiry.

Settlement fee rate is snapshotted at 5%. It is blocked until `FINANCE_FEE_BASIS` and
`FINANCE_FEE_ROUNDING` are explicitly approved. The current supported basis is
`TOTAL_COLLECTED`; rounding can be HALF_UP, DOWN or UP. Shipping, discounts, deductions
and net-of-refund basis alternatives need an explicit implementation if chosen.

Payouts API/account entitlement has not been activated or verified. The current workflow
is explicitly **manual**, with no payOS payout webhook and no automated transfer call:

1. Create READY settlement after checking eligibility.
2. Begin payout rechecks all blockers under the same per-order lock and creates a stable
   manual reference `mss-settlement-{id}`. State becomes PROCESSING, funds remain HELD.
3. The authorized operator makes one bank transfer and reconciles that reference first
   if the result is uncertain. A second payout request never creates a new transfer.
4. Confirm requires the successful bank reference and HTTPS proof. Only then does the
   settlement become SUCCESS and payment allocation become RELEASED. The fee and payout
   ledger entries are distinct and sum to the collected gross snapshot.

Once a manual payout is PROCESSING, new dispute/cancellation/refund mutations return
`PAYOUT_ALREADY_RESERVED`; the operator must reconcile the in-flight transfer. This
serialize-or-reject rule closes the dispute/payout race; never bypass it with SQL edits.
If transfers need automatic retries, add a provider payout adapter and outcome queries
before enabling them. Payout webhook signatures must use the payout-specific SDK rules,
not the collection verifier. No failed/unknown manual transfer is treated as successful.
Disputes can still be opened after completed payouts for investigation; this does not
reverse a confirmed bank transfer or invent a recoverable refund balance.

## API implemented now

All routes below use Gateway `/api/payments`. Commands marked K require an
`Idempotency-Key` of 8–100 ASCII letters/digits/colon/underscore/hyphen. Reusing a key
with another command/body is rejected. Ownership and operator rights are checked server side.

| Method / suffix | Audience | Purpose |
|---|---|---|
| GET `/orders/{orderId}` | Owner, supplier, ADMIN | Context, obligations, cases, eligibility, latest 200 audit entries |
| POST `/{paymentId}/initiate` K | Buyer | Initiate server-owned obligation; cancelled/expired attempts can be replaced |
| POST `/{paymentId}/reconcile` | Parties, ADMIN | Query payOS; no frontend success flag |
| POST `/{paymentId}/cancel-link` | Buyer | Cancel unpaid provider link, not collected funds |
| POST `/payos/callback` | payOS signature | Collection webhook |
| POST `/orders/{orderId}/cancellations` K | Parties | `{reason}` |
| POST `/orders/{orderId}/cancellations/{id}/decision` K | ADMIN | `{approve,reason}` |
| POST `/orders/{orderId}/disputes` K | Parties | `{reason,evidenceUrl?}` |
| POST `/orders/{orderId}/disputes/{id}/evidence` K | Parties | `{evidenceUrl}` |
| POST `/orders/{orderId}/disputes/{id}/resolve` K | Configured ADMIN | `{reason}` as resolution |
| POST `/orders/{orderId}/refunds` K | ADMIN | `{paymentId,amount,reason,policySnapshot}` |
| POST `/orders/{orderId}/refunds/{id}/reject` K | ADMIN | `{reason}` |
| POST `/orders/{orderId}/refunds/{id}/confirm` K | ADMIN | `{reference,evidenceUrl}` |
| POST `/orders/{orderId}/settlements` K | ADMIN | Snapshot eligible settlement |
| POST `/orders/{orderId}/settlements/{id}/payout` K | ADMIN | Reserve one manual payout |
| POST `/orders/{orderId}/settlements/{id}/confirm` K | ADMIN | `{reference,evidenceUrl}` |

Suppliers receive no checkout URL or QR credential in the aggregate response. Error
responses contain stable `code`, never raw SDK messages, SQL, secrets or bank payloads.
OpenAPI schemas are generated from the controllers at Commerce `/v3/api-docs`.

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
- Refund matrix/version, partial refund settlement treatment, manual approval authority.
- Dispute adjudicator, SLA and in-flight payout complaint escalation process.
- Fee basis, rounding and exact TV6 receipt/acceptance conditions.
- payOS Payouts entitlement, bank destination verification and automatic payout adapter.
- Broker transport, consumer bindings, public webhook registration/tunnel and TV1 JWT hook.

## Verification

`mvn -pl CommerceService,API-Gateway -am test` runs service tests, concurrency tests and
real SDK signature tests without contacting payOS or moving money. H2 tests execute V1+V2
with documented syntax adaptations; they do not substitute for SQL Server validation.
`scripts/tv5-sqlserver-check.ps1` applies the original migrations to a uniquely named local
SQL Server test database, checks filtered uniqueness and integer-VND constraints, then
drops only that database. It uses Windows authentication and leaves production DBs alone.

Frontend: `npm run lint` and `npm run build`. An end-to-end payment with a real bank is
still dependent on TV1/TV3/TV6 integration and public channel webhook configuration.

Official SDK reference: https://github.com/payOSHQ/payos-lib-java
Collection API reference: https://payos.vn/docs/api/
