# ADR-024: Define Release 0.3 immutable Reconciliation, settlement posting, and controlled correction semantics

Status: Accepted  
Date: 2026-07-27  
Decision owners: Product owner; Architecture owner  
Supersedes: Unresolved settlement-posting and correction details in Technical Specification v1.6  
Superseded by: None

## Summary

Release 0.3 keeps Reconciliation internal, snapshots immutable financial facts, creates immutable runs/results, designates one current run through a separately locked pointer, and posts settlement movements only from stable `SettlementPostingInstruction` identities.

Corrections exact-compensate only an invalidated settlement adjustment through an authorised Case. Payment and Reversal postings are never manually normalised.

## Context

Product and Technical v1.6 require settlement ingestion, immutable reruns, deterministic discrepancies, Casework, and compensating corrections. They do not define exact settlement accounting, stable financial identity across reruns, current-run/posting concurrency, or correction eligibility.

Using `reconciliationResultId` as Ledger source is unsafe because each rerun creates new results and could duplicate a financial effect.

## Decision

The accepted external file contract is `docs/api/provider-settlement-file-v1.md`. Its field order, limits, dual record identity, and deterministic matching rules are normative for Slice 7 and later Reconciliation work.


### Ownership

Reconciliation remains an internal Core module in Release 0.3 and owns:

- settlement batch families and immutable versions;
- normalized settlement record versions;
- immutable internal financial snapshots;
- runs/results/discrepancies;
- current-run designation;
- Reconciliation-owned current subject status/history; and
- `SettlementPostingInstruction` business identities.

Ledger owns settlement/correction posting templates and persistence. Casework owns investigation, CorrectionRequest, reason, and resolution. Payment/Provider own Payment/Reversal recovery.

Reconciliation never writes Payment, Provider, Ledger, or Casework tables.

### Raw file and normalized record identity

Raw bytes are written to an immutable content-addressed S3/MinIO key outside a database transaction while computing SHA-256. A short PostgreSQL transaction then inserts or finds metadata.

A `SettlementBatchFamily` is unique by:

```text
tenantId + providerId + providerBatchReference + settlementPeriod
```

A `SettlementBatchVersion` is immutable and unique by family plus file content hash. Exact duplicate upload returns the existing version. Corrected content creates a new version in the same family with explicit supersession.

Normalized content is represented in two layers:

1. `CanonicalSettlementRecordVersion`, unique by:

```text
tenantId + providerId + providerRecordKey + normalizedContentHash
```

2. `SettlementRecordOccurrence`, unique by:

```text
tenantId + settlementBatchVersionId + rowNumber
```

Every physical file row has an occurrence that references one canonical record version and preserves validation result/original row position. Provider record key is a matching key, not an occurrence uniqueness constraint: repeated identical rows remain distinct occurrences referencing the same canonical record, while conflicting content creates another canonical version. This preserves `DUPLICATE_PROVIDER_RECORD` evidence and also lets unchanged rows in a corrected file reuse the same canonical identity and prior settlement instruction. Reruns reuse the same occurrences.

### Reconciliation snapshot

A run captures:

- batch/version;
- run number;
- rules version;
- source cutoff;
- immutable normalized records; and
- immutable Payment/Reversal financial event facts loaded in bounded pages through published APIs.

The snapshot uses immutable completion/reversal Provider and Ledger evidence, not mutable Payment status as the financial source of truth. This makes the run reproducible without a long cross-module transaction.

### Batch, run, and current designation

Settlement Batch lifecycle remains exactly:

```text
RECEIVED -> VALIDATING -> READY -> PROCESSING -> COMPLETED | COMPLETED_WITH_DISCREPANCIES | FAILED
```

File-identity or structural failure produces `FAILED` and no normalized import. Record-level validation errors are preserved in the validation summary. A Reconciliation Analyst must explicitly confirm import from `READY`; valid rows are imported and invalid rows remain quarantined evidence, producing `COMPLETED_WITH_DISCREPANCIES`. No record is silently dropped.

Run lifecycle remains:

```text
QUEUED -> RUNNING -> COMPLETED | COMPLETED_WITH_DISCREPANCIES | FAILED | CANCELLED
```

Run result content never changes.

A separate `CurrentReconciliationRun` pointer identifies exactly one current run per:

```text
tenantId + batchFamilyId
```

Current designation is not stored by mutating historical results.

A Reconciliation-owned `BatchFamilyControl` row is the first lock acquired by every run promotion, settlement posting, and settlement correction operation for that family. This serializes pointer, posting, and correction decisions.

Promotion locks `BatchFamilyControl` and the current pointer. Promotion is blocked when the candidate run would invalidate an uncompensated settlement posting from the current run. The candidate remains completed but non-current until required Cases/corrections finish.

A settlement-posting transaction locks the same control/pointer and revalidates that the originating run is current immediately before Ledger application. A superseded run cannot create a new financial effect.

### Reconciliation status ownership

`ReconciliationStatus` remains separate from `PaymentStatus` and is owned by Reconciliation.

Reconciliation stores append-only subject history and a current projection for:

```text
subjectType = PAYMENT | REVERSAL
subjectId   = paymentId | reversalId
```

Payment tables are not updated with Reconciliation status. Payment search/detail composes or projects the Reconciliation-owned status.

Status meaning is exact:

- `NOT_APPLICABLE`: the Payment/Reversal is not yet a completed financial subject;
- `AWAITING_BATCH`: completed financial subject has no applicable current batch/run result;
- `PENDING`: included in a current run that has not produced a final subject result;
- `MATCHED`: current run produced one exact eligible match;
- `DISCREPANCY`: current run produced one or more discrepancy results.

Current status is a projection from append-only history. Current-run replacement appends a new subject status; it never rewrites prior status evidence.

### Deterministic matching

Matching remains exact and explainable. A settlement record version can match at most one internal financial subject. Duplicate provider rows, ambiguous identifiers, changed subject matches, or incompatible status create discrepancies and no posting.

Fuzzy matching is excluded.

### SettlementPostingInstruction

A Ledger posting is never identified by run-result ID.

Reconciliation creates or finds one immutable `SettlementPostingInstruction` for an eligible canonical match. It contains:

- `settlementPostingId`;
- Tenant;
- canonical settlement record version;
- originating settlement record occurrence/current-run evidence;
- subject type/ID;
- template version;
- amount/currency;
- required original Payment/Reversal Ledger evidence;
- canonical content hash; and
- creation/current-run evidence.

Business uniqueness is equivalent to:

```text
UNIQUE (
  tenant_id,
  canonical_settlement_record_version_id,
  subject_type,
  subject_id,
  template_version
)
```

Equivalent reruns or corrected files containing the same canonical record/subject return the same instruction. Changed normalized content creates a new canonical version and cannot reuse the old identity. A posting instruction is created only when the current run has exactly one valid occurrence for that canonical record; duplicate/conflicting occurrences create discrepancies and no posting.

Ledger source identity is:

```text
tenantId + SETTLEMENT_ADJUSTMENT + settlementPostingId
```

This identity is stable across reruns and restart.

### Settlement posting application

Each immutable instruction has one `SettlementPostingApplication` row unique by instruction ID. Its status is exactly:

```text
PENDING | POSTED
```

The instruction and `PENDING` application are created together. A restartable worker transaction locks `BatchFamilyControl`, the current pointer, the instruction/application, and then calls `ledger::api`. It revalidates that the run is current and the match/template is unchanged. Exact posting/replay and the transition to `POSTED` commit once with the Ledger transaction ID. Failure rolls back to `PENDING`; a separate bounded failure record preserves attempt/error evidence for retry and operations. A superseded run's pending instruction cannot post unless a later current run references the exact same instruction.

### Exact settlement templates

For an exact current Payment settlement match:

```text
DEBIT  SETTLEMENT_RECEIVABLE
CREDIT PROVIDER_CLEARING
```

For an exact current completed-Reversal settlement match:

```text
DEBIT  PROVIDER_CLEARING
CREDIT SETTLEMENT_RECEIVABLE
```

Both use full matched amount/currency, exactly two entries, no compensation reference, and exact replay.

A Reversal settlement posting is eligible only when the corresponding Payment settlement posting exists, is exact, and remains uncompensated. Otherwise Reconciliation records a `REVERSAL_WITHOUT_PAYMENT_SETTLEMENT` discrepancy and posts nothing. When Payment and Reversal records occur in one batch, Payment settlement is applied before Reversal settlement.

### Changed or corrected evidence

A posting instruction/application can produce at most one Ledger transaction. If later evidence invalidates it:

1. a discrepancy/Case is created;
2. the original posting is exact-compensated through the correction workflow;
3. a corrected record version or explicitly approved changed match creates a new posting instruction; and
4. the new current run may then be promoted and applied.

A run cannot silently remap an already-posted immutable record version to a different internal subject.

### CorrectionRequest

Casework owns `CorrectionRequest`; Ledger owns compensation.

Status:

```text
REQUESTED -> PROCESSING
PROCESSING -> COMPLETED | FAILED
FAILED -> PROCESSING
```

Release 0.3 correction kind is exactly:

```text
COMPENSATE_SETTLEMENT_ADJUSTMENT
```

Eligibility requires an original Ledger transaction that:

- has source `SETTLEMENT_ADJUSTMENT`;
- belongs to the same Tenant;
- is linked to the discrepancy/Case and posting instruction;
- is invalidated by approved Case evidence;
- is not already compensated; and
- is not itself compensating.

Payment and Reversal postings are ineligible.

### Exact correction posting

Ledger creates the exact inverse of the original settlement adjustment:

- same Tenant, currency, accounts, and amounts;
- every debit becomes credit and every credit becomes debit;
- source type `AUTHORISED_CORRECTION`;
- source ID `correctionId`;
- mandatory compensation reference to the original settlement transaction.

Financial identity:

```text
tenantId + AUTHORISED_CORRECTION + correctionId
```

PostgreSQL prevents a second completed compensation for the same target transaction.

### Correction transaction and lock order

Casework owns one short transaction. It acquires locks in this order:

1. Reconciliation `BatchFamilyControl`;
2. current-run pointer and settlement posting instruction;
3. Case;
4. CorrectionRequest;
5. Ledger posting rows through `ledger::api`.

It revalidates current/invalidation evidence, posts or exactly replays compensation, completes CorrectionRequest, records Case history/outbox/audit, and commits once. Ledger joins and never uses `REQUIRES_NEW`.

### Case closure

A Case requiring correction cannot become `RESOLVED` or `CLOSED` until CorrectionRequest is `COMPLETED` and exact Ledger evidence exists.

Provider/Payment evidence inconsistencies use owner-specific recovery. Missing internal Payment, Payment/Reversal posting inconsistency, or unsupported financial adjustment opens a critical Case and never fabricates or normalises records.

## Consequences

Positive:

- Settlement source identity is stable across reruns and restart.
- Current-run promotion cannot race with posting/correction.
- Corrected evidence preserves original posting, compensation, and replacement posting.
- Reconciliation status remains separate and module-owned.
- Arbitrary journal administration is impossible.

Negative or costly:

- Batch family control, posting instruction, status history, and immutable snapshots add persistence.
- Corrected financial evidence may require a Case and compensation before promotion.
- The supported correction is deliberately narrow.

## Alternatives considered

### Use `reconciliationResultId` as source

Rejected because reruns create new result IDs and could duplicate financial effects.

### Use only settlement record version as source

Rejected because a corrected matching rule may require a new approved instruction after compensation; the explicit instruction identity models that decision safely.

### Mutate old run results or postings

Rejected because both are immutable.

### Arbitrary debit/credit correction UI

Rejected because it bypasses source-specific financial invariants.

## Impact assessment

- Product: REC-01 through REC-05, CAS-01 through CAS-05, LED-03/04, BR-03/09/13/15/17/18.
- Data: batch family/version, record versions, snapshots, current pointer, batch-family control, subject status history, posting instruction, CorrectionRequest, compensation uniqueness.
- Module ownership: Reconciliation detects/snapshots/matches/instructs; Ledger posts; Casework authorises/resolves.
- Testing: duplicate/corrected upload, immutable rerun, current-pointer races, instruction replay, restart, 100,000 records, settlement sequence, correction eligibility, lock ordering, rollback, and isolation.

## Review conditions

Reconsider through a new ADR if real settlement/payout, fees/loss accounts, partial adjustments, general journal administration, or Reconciliation extraction is introduced.

## Approval

- Product owner: Approved by delegated instruction on 2026-07-27
- Architecture owner: Approved
- Approved deviations recorded in authoritative documents: Product Definition v1.7 and Technical Specification v1.7
