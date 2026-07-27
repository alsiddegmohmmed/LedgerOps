# Release 0.3 Slice 8 - Immutable Reconciliation, current-run promotion, and settlement posting

Status: Pending  
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

- Changed: Pending
- Verified: Pending
- Incomplete: Slice 8
- Deviations: None
