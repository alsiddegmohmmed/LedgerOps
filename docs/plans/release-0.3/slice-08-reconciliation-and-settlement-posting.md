# Release 0.3 Slice 8 - Immutable Reconciliation, current-run promotion, and settlement posting

Status: Complete
Owner: One implementation owner  
Release: 0.3

## Outcome

Reconciliation snapshots immutable financial facts, matches deterministically, preserves every run, designates one current run safely, creates discrepancies/Cases, and applies exact restart-safe settlement postings through stable instructions.

## Authority

REC-02 through REC-05; LED-03; CAS-01 source path; BR-12/13/15/17; ADR-010, ADR-024, ADR-025.

## Scope

- immutable financial-event snapshot APIs/pages/cutoff/hash;
- Reconciliation run/result/discrepancy lifecycle and exact catalogue;
- exact NOT_APPLICABLE/AWAITING_BATCH/PENDING/MATCHED/DISCREPANCY subject status history/current projection outside Payment tables;
- BatchFamilyControl and CurrentReconciliationRun pointer;
- promotion eligibility/blocking/serialization;
- deterministic exact matching and changed-match conflict;
- stable SettlementPostingInstruction keyed by canonical record version/subject/template plus one PENDING/POSTED SettlementPostingApplication; duplicate occurrences never post;
- Payment and Reversal settlement templates;
- Payment-before-Reversal settlement prerequisite/order;
- focused Ledger posting/replay API in restartable item transaction;
- discrepancy `CreateCaseRequested` commands;
- run/detail/difference/current/posting UI.

## Implemented behavior

The implementation uses the approved module boundaries and published evidence
ports. Reconciliation owns the immutable snapshot, run, result, discrepancy,
current-run, status-history, and posting-instruction records. Payment, Provider,
and Ledger expose only the bounded evidence needed by Reconciliation; the
Reconciliation module does not read their tables directly.

The engine captures settlement occurrences and completed Payment/Reversal
subjects through bounded pages. The Payment evidence query filters accepted
final Provider results by the run's `sourceCutoff` and derives financial status
from that immutable final result. The snapshot stores Provider and Ledger
evidence, completion counts, and a SHA-256 digest before the run is evaluated.

Matching is exact and deterministic. It records the closed discrepancy
catalogue from REC-03, including missing internal/provider records, duplicate
records or references, amount/currency/status/settlement-date mismatches,
missing or mismatched Ledger evidence, invalid Provider records, unresolved
references, and `REVERSAL_WITHOUT_PAYMENT_SETTLEMENT`. Internal subjects with
no matching settlement occurrence receive an immutable missing-provider result;
this keeps both sides of the comparison visible.

Case commands use a stable Case identity derived from the Tenant and immutable
result ID. The severity mapping is an implementation detail derived from the
Product severity bands: critical for missing internal evidence, duplicate or
financial-effect inconsistencies; high for missing Provider evidence and
Provider/status/currency/reference validity issues; and medium for settlement
date mismatch. Slice 8 does not define a new discrepancy SLA, so `dueAt`
equals the discrepancy occurrence time. Slice 4's 24-hour policy remains
specific to RiskReview and is not applied here.

Promotion and posting acquire `BatchFamilyControl` first. A current pointer is
unique per Tenant and batch family. Stable posting instructions are keyed by
canonical record version, subject, and template version. Payment settlement
posts `DEBIT SETTLEMENT_RECEIVABLE / CREDIT PROVIDER_CLEARING`; Reversal
settlement posts the inverse. A Reversal waits for the exact Payment settlement
application. Ledger posting is called through `ledger::api` inside the bounded
restart-safe item transaction; no external Provider or Keycloak call occurs in
that transaction.

The Operations Web provides run creation, immutable run/result/posting reads,
current-run visibility, controlled promotion, posting preparation, and explicit
posting actions. Support sessions can inspect the pages but cannot mutate
reconciliation state. No Notification consumer is implemented in Slice 8;
Slice 10 owns notification creation and read state.

Excluded:

- CorrectionRequest/compensation;
- Reconciliation extraction;
- fuzzy matching.

## Critical invariants

- result content immutable;
- exactly one current pointer per family/period;
- promotion/posting uses BatchFamilyControl first;
- superseded run cannot post;
- instruction identity is stable across reruns and restart;
- one record version matches at most one subject;
- Reversal settlement requires uncompensated Payment settlement;
- Reconciliation status never writes Payment tables;
- no direct cross-module table access.

## Verification

- deterministic fixtures for every discrepancy category;
- source cutoff reproducibility and immutable-fact snapshots;
- concurrent run completion/promotion/posting;
- promotion blocked by invalidated uncompensated posting;
- same rerun returns same instruction/application/posting; failure rollback remains PENDING with separate failure evidence;
- changed match conflict;
- Spring Batch crash/restart/exact replay;
- Payment and Reversal accounting/sequence;
- duplicate case command and Case uniqueness;
- tenant isolation, Modulith graph, 100,000-record match evidence;
- full commands.

## Completion report

- Changed: Foundation evidence ports and immutable snapshot capture; exact
  reconciliation matching and result/status history; current-run promotion;
  stable settlement posting instructions/applications; Ledger settlement API;
  reconciliation Case commands; authenticated API routes; Operations Web
  pages/actions; Flyway migrations V37–V40; focused schema and engine tests.
- Verified:
  - `./gradlew :test --tests 'com.ledgerops.reconciliation.*' --console=plain` — BUILD SUCCESSFUL.
  - `./gradlew :test --tests 'com.ledgerops.reconciliation.infrastructure.SettlementSchemaIntegrationTests' --console=plain` — BUILD SUCCESSFUL.
  - Operations Web `corepack pnpm typecheck` — passed.
  - Operations Web `corepack pnpm test` — 6 files and 24 tests passed.
  - Operations Web lint, typecheck, tests, and build pass under Node 24.18.0. Earlier Node 22 output is historical and is not final evidence.
  - `git diff --check` — passed for the final Slice 8 diff.
- Incomplete: The earlier repository-wide check was interrupted by transient Keycloak image startup failures. The current branch has since passed the final `./gradlew clean test --console=plain --stacktrace` and `./gradlew check --console=plain --stacktrace` gates; remaining incomplete items are tracked centrally in Slice 11.
- Deviations: None from the approved Slice 8 scope. Notification consumption, correction/compensation, and fuzzy reconciliation remain deferred as documented.
