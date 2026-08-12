# Release 0.3 Slice 9 - Controlled settlement corrections and complete Case resolution

Status: Verified in isolated extraction; integration into the main worktree pending
Owner: One implementation owner  
Release: 0.3

## Outcome

A Case can exact-compensate one invalidated settlement adjustment through a fixed template. Promotion/correction races serialize, arbitrary accounts are impossible, and required effects gate Case resolution/closure.

## Authority

CAS-01 through CAS-05; LED-04; REC-03/04/05; IAM-04; AUD-01; BR-03/07/08/09/18; ADR-014, ADR-024, ADR-025.

## Scope

- CorrectionRequest aggregate/lifecycle/persistence;
- `COMPENSATE_SETTLEMENT_ADJUSTMENT` only;
- correction permission, confirmation, reason, audit;
- Reconciliation lock/eligibility API;
- global lock order from ADR-024;
- exact inverse Ledger compensation and one-per-target uniqueness;
- atomic Reconciliation evidence/Case/Correction/Ledger/outbox/audit;
- corrected run promotion and replacement posting demonstration;
- Payment/Provider/Reversal owner-specific recovery Case paths;
- complete Case resolution/closure/reopen UI.

Excluded:

- arbitrary journals, account selection, partial differences;
- manual Payment/Reversal correction;
- fees/loss accounts;
- approval chains.

## Critical invariants

- only invalidated uncompensated SETTLEMENT_ADJUSTMENT eligible;
- same Tenant/Case/discrepancy/instruction evidence;
- exact inverse/same amount/currency/accounts;
- source `tenantId + AUTHORISED_CORRECTION + correctionId`;
- mandatory compensation reference;
- one completed compensation per target;
- Case cannot resolve/close until required exact effect;
- Payment/Reversal inconsistency never normalized.

## Verification

- lifecycle and concurrent request/retry;
- pointer/promotion/correction lock races and deadlock-safe order;
- every eligibility rejection;
- failpoint rollback and exact replay;
- changed correction content conflict;
- corrected file -> blocked run -> correction -> promotion -> replacement posting end-to-end;
- cross-Tenant/Merchant/permission denial;
- UI confirmation/evidence/close/reopen;
- full commands.

## Completion report

- Changed: Controlled settlement-correction lifecycle, reconciliation eligibility and locking API, exact inverse Ledger compensation, Case resolution/closure/reopen gating, Slice 9 API/UI flow, V41 migration, and focused domain/integration/browser tests.
- Verified: The isolated extraction passed the focused correction and modularity suites, the full root `./gradlew :test --no-daemon --console=plain` task, `./gradlew check --no-daemon --console=plain`, `git diff --check`, frontend typecheck/tests/build/focused lint, and the Slice 9 Playwright casework flow. The full root test task completed successfully in 8m09s.
- Incomplete: The verified extraction has not been copied, committed, or merged into the user's main worktree. Release-wide closure, Slice 10, and Slice 11 remain separate work. Full frontend lint still has pre-existing failures in unrelated reconciliation and settlement pages and was not changed by Slice 9.
- Deviations: None from the approved Slice 9 authority. The evidence is explicitly isolated and must not be described as merged or release-complete until integrated and reverified.
