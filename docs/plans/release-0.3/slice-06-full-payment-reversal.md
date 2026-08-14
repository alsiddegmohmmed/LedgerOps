# Release 0.3 Slice 6 - Full-payment Reversal

Status: Implemented — automated backend/frontend verification complete
Owner: One implementation owner  
Release: 0.3

## Outcome

One authorised full Reversal per completed Payment uses immutable attempts and ambiguity-safe Provider processing. Provider success atomically posts one exact compensation, completes Reversal, and changes Payment to REVERSED.

## Authority

PAY-06, PAY-08; PRV-02/04; LED-01/03/04; BR-03/06/07/08/13/14; ADR-016, ADR-020, ADR-021, ADR-023, ADR-025.

## Scope

- Reversal aggregate/persistence/uniqueness/API;
- request confirmation/reason/audit;
- typed Payment Attempt subject migration/backfill;
- Reversal Provider commands/results/work/mappings/webhooks/status recovery;
- authorised safe retry with `ReversalRetryApplication` unique by Tenant/Reversal/previous attempt, maximum three attempts;
- exact inverse ADR-020 Ledger API/template/replay;
- one-compensation-per-target database safeguard;
- atomic inbox/evidence/Ledger/Reversal/Payment/outbox/audit completion;
- Reversal detail/timeline/eligibility/retry UI;
- unresolved/failed Case request evidence.

Excluded:

- partial/cumulative Reversal;
- automatic Reversal resubmission;
- `REVERSAL_PAYABLE` posting;
- settlement movement.

## Critical invariants

- unique one Reversal per Payment;
- amount/currency copied from Payment;
- Payment stays COMPLETED until atomic success;
- exact posting Dr MERCHANT_PAYABLE / Cr PROVIDER_CLEARING;
- source `tenantId + REVERSAL + reversalId`;
- mandatory original ADR-020 compensation link;
- terminal replay validates complete shape;
- ambiguity never resubmits;
- no network call in transaction.

## Verification

- exhaustive transitions/terminal states;
- concurrent request/start/result/retry-application exact replay;
- attempt sequence/idempotency/hash/migration;
- Provider response/status/webhook duplicates/conflicts/out-of-order;
- all transaction failpoints and final database state;
- exact accounts/directions/amount/currency/source/compensation;
- tenant/Merchant/permission denial;
- UI confirmation/reason/evidence;
- full commands.

## Completion report

- Changed:
  - Added the pure full-Reversal aggregate, one-Reversal-per-Payment persistence, typed Payment Attempt subject migration/backfill, Provider operation identity, and versioned Reversal Provider result contract.
  - Added authorized request/processing/retry application services with confirmation/reason/audit, immutable retry applications, bounded three-attempt history, and no automatic Reversal resubmission.
  - Added the `ledger::api` exact inverse compensation boundary, accepted final Reversal evidence, one-compensation-per-target database safeguard, and atomic Provider-result completion that changes Reversal to `COMPLETED` and Payment to `REVERSED` together.
  - Added Reversal HTTP actions, the Payment operational-detail Reversal snapshot, correct Reversal lifecycle timeline attribution, and Operations Web request/retry/detail evidence with support-mode read-only behavior.
- Verified:
  - Focused Slice 6 backend tests, including domain, request/processing/result/retry services, persistence migrations, Provider command/result routing, contracts, modularity, and the Reversal timeline regression, pass.
  - Operations Web TypeScript, lint, unit tests, and production build pass under the required Node 24.18.0 runtime. Earlier Node 22 output is historical and is not final evidence.
  - Root `GRADLE_USER_HOME=/Users/Siddegx/.gradle ./gradlew :check --console=plain` passes after preserving typed PAYMENT identity for legacy Provider webhook interactions/results.
  - The dedicated authenticated Operations Web Playwright test passes: it signs in, selects the authorised Tenant/Merchant scope, opens a completed Payment, submits a confirmed full Reversal, verifies the `REQUESTED` response, and verifies the state after reload.
  - `git diff --check` passes.
- Incomplete:
  - The dedicated Slice 6 Playwright configuration now covers the asynchronous Provider completion/retry path and passes; release-wide regression/closure remains a later release gate.
  - Slice 7–10 evidence is no longer pending as implementation work; final release-level evidence remains governed by Slice 11.
- Deviations: None
