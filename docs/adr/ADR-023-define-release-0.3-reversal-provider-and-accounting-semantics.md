# ADR-023: Define Release 0.3 Reversal, Provider, Payment Attempt, and accounting semantics

Status: Accepted  
Date: 2026-07-27  
Decision owners: Product owner; Architecture owner  
Supersedes: Unresolved Reversal accounting and Provider details in Technical Specification v1.6  
Superseded by: None

## Summary

Release 0.3 implements exactly one full Reversal workflow for one originating `COMPLETED` Payment.

The completed Reversal posts the exact inverse of ADR-020:

```text
DEBIT  MERCHANT_PAYABLE
CREDIT PROVIDER_CLEARING
```

for the full original Payment amount/currency, with source `tenantId + REVERSAL + reversalId` and a mandatory compensation reference to the original Payment Ledger transaction.

## Context

Product and Technical v1.6 require one full Reversal, immutable retry history, Provider-confirmed success, one compensating transaction, and atomic `Reversal COMPLETED` plus `Payment REVERSED`. They do not define exact accounts, source identity, replay, or how Release 0.2 Payment Attempts and Provider work extend to Reversal.

The account catalogue contains `REVERSAL_PAYABLE`, but BR-14 requires the original financial effect to remain active until successful Reversal completion. A pre-success obligation posting would contradict that boundary.

## Decision

### Ownership

Reversal is a separate aggregate inside the existing Payment module.

Payment owns Reversal lifecycle, one-Reversal-per-Payment eligibility, Reversal-associated Payment Attempts, accepted final Reversal evidence, atomic completion orchestration, and lifecycle events.

Provider owns adapters, work, interactions, results, ambiguity/status recovery, webhook evidence, and retry-safety evidence.

Ledger owns original posting lookup, exact Reversal template, account validation, posting, compensation linkage, and replay validation.

Payment never queries Provider or Ledger tables. Provider never queries Payment/Reversal tables.

### Reversal aggregate

A Reversal contains at least:

- `reversalId`
- `tenantId`
- `paymentId`
- `merchantId`
- immutable full original `Money`
- `status`
- request reason/actor/time
- version
- processing/failed/completed timestamps
- failure category where present

PostgreSQL enforces `UNIQUE (tenant_id, payment_id)`.

Status is exactly:

```text
REQUESTED | PROCESSING | FAILED | COMPLETED
```

Transitions:

```text
REQUESTED  -> PROCESSING
PROCESSING -> COMPLETED | FAILED
FAILED     -> PROCESSING
```

`FAILED -> PROCESSING` requires an authorised safe retry.

### Request transaction

One short Payment-owned transaction:

1. validates `reversal:request`, confirmation, reason, Tenant/Merchant scope;
2. locks the Payment;
3. requires `COMPLETED`;
4. verifies exact ADR-020 posting evidence through `ledger::api`;
5. verifies no Reversal exists;
6. creates `REQUESTED` Reversal;
7. appends lifecycle/audit evidence; and
8. commits once.

No Provider call or Ledger posting occurs.

### Payment Attempt association

Release 0.3 extends Payment Attempt with a typed subject:

```text
attemptSubjectType = PAYMENT | REVERSAL
attemptSubjectId   = paymentId | reversalId
paymentId          = originating Payment in both cases
```

Existing Release 0.2 rows backfill as `PAYMENT`.

Uniqueness is:

```text
UNIQUE (tenant_id, attempt_subject_type, attempt_subject_id, sequence)
```

No `ReversalAttempt` type is introduced.

Reversal Provider idempotency key is:

```text
reversal:<lowercase canonical reversal UUID>
```

Every attempt for one Reversal reuses it.

Reversal request-intent hash is SHA-256 over canonical JSON containing, in order:

```text
providerId
operationType = REVERSAL
paymentId
reversalId
merchantId
normalized full amount
currency
originalProviderReference
```

### Initial processing

One transaction locks Payment then Reversal, requires Payment `COMPLETED` and Reversal `REQUESTED`, creates sequence-1 Reversal attempt, moves Reversal to `PROCESSING`, appends `SubmitReversalToProvider`, writes audit, and commits.

Provider HTTP occurs later outside database transactions.

### Provider contracts and result behavior

Provider work has business operation `PAYMENT | REVERSAL`; work type remains `SUBMISSION | STATUS_QUERY`.

Release 0.3 adds versioned contracts:

- `SubmitReversalToProvider`
- `ProviderReversalResultObserved`
- `ReversalRequested`
- `ReversalProcessingStarted`
- `ReversalFailed`
- `ReversalCompleted`

Payment ID remains the Kafka partition key.

Result behavior:

| Evidence | Reversal behavior |
|---|---|
| `SUCCESS / NOT_RETRYABLE` | Apply atomic completion. |
| `DECLINED / NOT_RETRYABLE` | `PROCESSING -> FAILED`; Payment remains `COMPLETED`. |
| definitive `PERMANENT_FAILURE / NOT_RETRYABLE` | `PROCESSING -> FAILED`. |
| `TEMPORARY_FAILURE / SAFE_TO_RESUBMIT` | `PROCESSING -> FAILED`; expose authorised retry. |
| `ACCEPTED`, `PENDING`, `UNKNOWN`, or recovery-required temporary failure | remain `PROCESSING`; perform status recovery; never blind-resubmit. |

Status recovery uses the ADR-021 timing/query limit. Reversal resubmission is never automatic.

Maximum Reversal attempts are three total: one initial plus at most two authorised safe retries. Status-recovery exhaustion leaves Reversal `PROCESSING`, marks Provider work `UNRESOLVED`, and exposes the operational investigation path without resubmission. Safe-resubmission attempt exhaustion leaves Reversal `FAILED` and exposes the operational investigation path. No Case is automatically created because the Product Case sources remain RiskReview and Reconciliation discrepancy.

An authorised Reversal retry locks Payment then Reversal, requires `FAILED`, verifies the latest attempt's durable `SAFE_TO_RESUBMIT` evidence, and creates the next attempt plus `SubmitReversalToProvider` atomically. A `ReversalRetryApplication` is unique by `(tenant_id, reversal_id, previous_attempt_id)`. Concurrent or repeated retry actions return the same created attempt/outbox after exact validation; different evidence/content under that identity is a typed consistency conflict.

### Exact accounting

After definitive Provider Reversal success, Ledger posts exactly two entries:

```text
DEBIT  MERCHANT_PAYABLE
CREDIT PROVIDER_CLEARING
```

Rules:

- full original Payment amount;
- original Payment currency;
- exactly two entries;
- source type `REVERSAL`;
- source ID `reversalId`;
- mandatory compensation reference to the original ADR-020 Payment transaction;
- no fee or settlement movement.

Financial source identity is:

```text
tenantId + REVERSAL + reversalId
```

`REVERSAL_PAYABLE` remains in the closed catalogue but is reserved for a future explicitly approved staged obligation model. Release 0.3 does not use it or post before Provider-confirmed success.

### Atomic completion

One Payment-owned PostgreSQL transaction:

1. inserts/verifies result-consumer inbox;
2. verifies immutable Provider evidence;
3. locks Payment then Reversal;
4. validates state/posting matrix;
5. loads original ADR-020 posting;
6. calls `ledger::api` to post or exactly replay compensation;
7. moves Reversal to `COMPLETED`;
8. moves Payment `COMPLETED -> REVERSED`;
9. persists accepted final Reversal evidence;
10. appends `ReversalCompleted`;
11. writes audit; and
12. commits once.

Ledger joins and never uses `REQUIRES_NEW`. Failure rolls back every effect.

### Replay and consistency

Exact replay requires matching Tenant, source, amount, currency, accounts, directions, entry count, and compensation target.

- `PROCESSING` plus no posting: perform completion.
- `PROCESSING` plus existing posting: critical inconsistency.
- `REVERSED` plus `COMPLETED` plus exact posting: return original result.
- terminal state with absent/different posting: critical inconsistency.
- success applied to `REQUESTED`/`FAILED`: lifecycle error.
- conflicting final Provider result: preserve evidence; never overwrite accepted final state.

PostgreSQL source uniqueness plus one-compensation-per-target safeguards prevent duplicates.

## Consequences

Positive:

- Reversal exactly offsets ADR-020 without mutation.
- Attempt, Provider, source, replay, and transaction identities are deterministic.
- Ambiguity cannot trigger blind resubmission.
- Original financial effect remains active until definitive success.

Negative or costly:

- Payment Attempt and Provider schemas/contracts require additive evolution.
- Reversal completion has a large but necessary joined transaction and test matrix.
- `REVERSAL_PAYABLE` remains unused in the baseline.

## Alternatives considered

### Two-stage posting through `REVERSAL_PAYABLE`

Rejected because it changes financial balances before Provider success.

### Create `ReversalAttempt`

Rejected because the approved Product concept is Payment Attempt associated with Payment or Reversal.

### Automatically resubmit Reversal

Rejected because Reversal is a high-risk financial action and the baseline requires authorised retry.

## Impact assessment

- Product: PAY-06, PAY-08, PRV-02/04, LED-01/03/04, BR-03/06/07/08/13/14.
- Data: Reversal, attempt subject, accepted final evidence, Provider operation mapping, exact uniqueness.
- Messaging: additive contracts and identities defined by ADR-025.
- Security: `reversal:request` and `reversal:retry`, confirmation, reason, current scope, audit.
- Testing: lifecycle, concurrency, attempt immutability, ambiguity, exact accounting, failpoints, replay, conflicts, isolation, and module boundaries.

## Review conditions

Reconsider through a new ADR if partial Reversal, staged obligation recognition, a different Provider safety model, or a different Ledger catalogue is required.

## Approval

- Product owner: Approved by delegated instruction on 2026-07-27
- Architecture owner: Approved
- Approved deviations recorded in authoritative documents: Product Definition v1.7 and Technical Specification v1.7
