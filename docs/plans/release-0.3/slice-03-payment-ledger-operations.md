# Release 0.3 Slice 3 - Payment/Ledger operations, timeline, notes, and audit search

Status: Implemented — backend verification complete
Owner: One implementation owner  
Release: 0.3

## Outcome

Authorised users can search Payments, inspect one composed detail/timeline, add immutable notes, navigate Ledger evidence, view balances/statements, and search Audit without cross-module table access.

## Authority

PAY-04, PAY-05, PAY-07; LED-03, LED-05; AUD-02; OPS-04; ADR-008, ADR-020, ADR-021, ADR-022, ADR-025.

## Scope

- Tenant/Merchant/permission-scoped Payment search with stable keyset pagination;
- filters required by PAY-05;
- published query APIs for Payment, Risk, Provider, Ledger, and existing Reconciliation placeholder;
- authoritative detail composition through APIs plus rebuildable timeline/search projection;
- append-only Payment notes with author/deactivation history;
- Ledger account balance and half-open date-bounded statement HTTP APIs;
- bidirectional Payment/Ledger links;
- Audit search by actor/Tenant/action/entity/date/result/correlation;
- Payment list/detail, notes, Ledger, and Audit UI.

Excluded:

- manual Risk decisions;
- Case/Reversal/Reconciliation implementation;
- dashboard aggregates.

## Critical invariants

- projections are never transactional truth;
- no cross-module repository/table access;
- pagination is deterministic under concurrent inserts;
- all filters enforce current Tenant/Merchant scope;
- note deletion is prohibited; edit history is explicit if editing is supported;
- displayed balances reconcile to immutable entries in one currency/time range;
- Reconciliation status is composed later from Reconciliation-owned data, never a Payment column.

## Verification

- cross-Tenant/Merchant negative query matrix;
- provider/customer/reference/date/amount/state/risk/reconciliation filter fixtures;
- stable pagination under insertion;
- projection rebuild and duplicate events;
- note immutability/deactivated author;
- balance/statement totals and boundaries;
- Payment-to-Ledger exact source navigation;
- normal demo read target within two seconds;
- UI keyboard/empty/error/loading states;
- full commands.

## Completion report

- Changed:
  - Added tenant/merchant/permission-scoped Payment search with deterministic keyset pagination, the approved filter set, composed Payment detail, provider/Risk/Ledger query contracts, and append-only Payment notes.
  - Added the V26 Payment-notes migration and V27 rebuildable Payment-timeline projection with duplicate-event protection and the ADR-027 lifecycle event path.
  - Added tenant-wide Ledger balance and half-open statement query APIs, Payment/Ledger navigation, and scoped Audit search with keyset pagination.
  - Added Operations Web Payment list/detail/note, Ledger, and Audit surfaces, including empty/error/loading/read-only/support-mode states and a CSRF-protected note BFF route.
  - Repaired the ADR-025 boundary violations inherited from the final Slice 2 administration changes: Identity no longer imports Merchant/Tenancy internals, and Administration no longer imports Identity internals.
- Verified:
  - `./gradlew :compileJava --console=plain` passed.
  - Slice 3 focused backend tests for Payment, Audit, and Ledger passed.
  - `ModularityTests` and `ArchitectureRulesTests` passed after the ADR-025 repair.
  - Non-Keycloak Identity domain/API/unit regression tests passed.
  - `git diff --check` passed.
  - Operations Web `pnpm typecheck` and `pnpm build` passed. Earlier Node 22 output is historical; final Release 0.3 frontend evidence is run under the required Node 24.18.0 runtime.
  - Full backend `./gradlew :test --max-workers=1 --console=plain` passed: 602 tests, 0 failures, 0 errors.
  - `./gradlew :check --max-workers=1 --console=plain` passed.
- Incomplete:
  - Browser Playwright/e2e verification was not completed.
  - The documented two-second demo-read target was not performance-measured in this slice.
- Deviations:
  - Ledger query authorization is tenant-wide because the current Ledger account contract does not expose Merchant ownership; no undocumented Merchant-scoped Ledger rule was invented.
  - Reconciliation status remains the ADR-024 placeholder composed from Reconciliation-owned semantics; no Payment reconciliation column was added.
  - No account-list endpoint was added because the approved Slice 3 contract specifies balance and statement queries, not account discovery.
