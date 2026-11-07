# Execution ledger — 2026-11-07-tv1-tv6-flow-repair.md

- Approved: DEPOSIT permits preparation; SHIPPING requires full payment. User requested implementation plus flow/rule document.
- Scope: Commerce lifecycle/security/cancellation/enum; FE real data; tests and documentation. No commits/push or production DB mutations.
- Ruling: apply to current checkout to preserve user's dirty workspace and deliver directly; no checkout/reset of existing edits. FE is a disjoint sidecar delegated under dispatching-parallel-agents; backend remains inline.
- Preflight: both payment collectors must use the same lifecycle service; shipment and finance writers must acquire customer-order lock before finance-context lock; cancel intent must block new links throughout remote calls.
- Ruling: customer order summary reports the least-progressed non-cancelled seller status, CANCELLED only when all cancelled; CANCEL_REQUESTED remains while cancellation is unresolved. Document and test these aggregate semantics.
- Baseline: JDK 21 exists at C:/Users/VO THANH KHOA/.jdks/ms-21.0.11. Tests use isolated H2 unless explicitly configured SQL test URL. No user DB credentials will be printed.
- Payment and cancellation regression tests failed on the original behavior, then FinanceServiceTest passed after updating the lifecycle and cancellation gate. The JPA regression reached shipment, acceptance and settlement eligibility and then reproduced the missing enum state before the enum update.
- Final independent review could not run because the agent reached its usage limit. The changes were checked locally.
- Frontend validation: 15 regression cases passed, TypeScript passed, Vite build passed.
