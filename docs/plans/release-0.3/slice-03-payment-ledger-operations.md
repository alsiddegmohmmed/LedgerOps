# Release 0.3 Slice 3 - Payment/Ledger operations, timeline, notes, and audit search

Status: Pending  
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

- Changed: Pending
- Verified: Pending
- Incomplete: Slice 3
- Deviations: None
