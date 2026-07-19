# ADR-016: Align payment, reversal, provider, reconciliation, and sensitive-action semantics

Status: Accepted  
Date: 2026-07-18  
Decision owners: Product owner; Architecture owner  
Supersedes: Conflicting lifecycle, ownership, provider-progress, and approval-chain text in the Product Definition v1.1 and Technical Specification v1.0  
Superseded by: None

## Context

The Product Definition v1.1 and Technical Specification v1.0 described incompatible Payment and Reversal state machines. The Technical Specification also contained these conflicts:

- It represented provider-submission and reversal progress as Payment states.
- It implied partial or cumulative reversals.
- It said provider results updated an immutable Payment Attempt.
- It treated every `FAILED` state as terminal despite the approved Reversal retry.
- It included formal maker-checker approval chains that the Product Definition deferred beyond the baseline.
- It conflated approval of the specification with acceptance of Portfolio Release 1.0.

These conflicts blocked Payment implementation. No Payment aggregate, Payment status enum, transition implementation, or persistence model was permitted until the authoritative documents described one coherent system.

### Product Definition sections affected

- Document control, revision history, footer version/date, and final implementation-authorization text
- §5.1 Core relationships
- §6.6 Payment reversal
- PAY-06 Payment reversal
- PAY-08 Reversal lifecycle visibility
- PRV-02 Provider attempt history
- BR-13 and BR-14
- §8.2 Payment lifecycle
- §8.4 Reversal lifecycle
- §14 Glossary

### Technical Specification sections affected

- Cover metadata, Document Control, Revision History, Approval Criteria, headers, and Appendix D implementation authorization
- §4.3 Module Ownership
- §5.1 Primary Aggregates
- §5.3 Payment State Machine
- §5.4 Reversal State Machine
- §5.5 Ledger Model
- §5.6 Atomic Payment Completion
- §5.7 Idempotency and Concurrency
- §8.3 Roles and Permissions
- §8.5 Sensitive Operations
- §9.2 Submission Workflow
- §9.3 Provider Result Categories
- §9.5 Webhook Reception and Processing
- §10.5 Correction Boundary
- §10.6 Correction Examples
- §13.1 Test Layers
- §13.2 Critical Verification Matrix
- §13.4 Release-Blocking Defects
- §14.3 Release 0.3
- §16 Architecture Decision Register
- §17.1 Functional Acceptance Criteria
- §17.3 Reliability and Security
- §17.4 Platform and Evidence release gate

## Decision

### Payment lifecycle

`PaymentStatus` contains exactly `CREATED`, `VALIDATING`, `RISK_REVIEW`, `APPROVED`, `REJECTED`, `PROCESSING`, `COMPLETED`, `FAILED`, and `REVERSED`.

The following transitions are allowed:

```text
CREATED -> VALIDATING
VALIDATING -> APPROVED | RISK_REVIEW | REJECTED
RISK_REVIEW -> APPROVED | REJECTED
APPROVED -> PROCESSING
PROCESSING -> COMPLETED | FAILED
COMPLETED -> REVERSED
```

`REJECTED`, `FAILED`, and `REVERSED` are terminal. `COMPLETED` requires provider success and exactly one corresponding balanced ledger transaction committed atomically. Reconciliation completion is independent.

### Reversal lifecycle

`ReversalStatus` contains exactly `REQUESTED`, `PROCESSING`, `FAILED`, and `COMPLETED`.

The following transitions are allowed:

```text
REQUESTED -> PROCESSING
PROCESSING -> COMPLETED | FAILED
FAILED -> PROCESSING
```

`FAILED -> PROCESSING` is permitted only when an authorised safe retry begins. One Reversal workflow exists per originating Payment. Each retry creates a new immutable provider attempt inside that Reversal workflow; previous attempts remain unchanged and visible.

`REQUESTED`, `PROCESSING`, and `FAILED` leave Payment `COMPLETED`. Reversal completion atomically posts one balanced compensating ledger transaction, marks the Reversal `COMPLETED`, and changes Payment from `COMPLETED` to `REVERSED`. Partial reversals, cumulative reversal amounts, additional Reversal aggregates for the same Payment, and multiple completed reversals are prohibited.

### Reconciliation and provider progress

`ReconciliationStatus` contains exactly `NOT_APPLICABLE`, `AWAITING_BATCH`, `PENDING`, `MATCHED`, and `DISCREPANCY`.

`ProviderResultCategory` contains exactly `SUCCESS`, `ACCEPTED`, `DECLINED`, `PENDING`, `TEMPORARY_FAILURE`, `PERMANENT_FAILURE`, and `UNKNOWN`.

Payment status, Reversal status, Reconciliation status, and provider-attempt progress are separate dimensions with independent histories. Provider submission, retry scheduling, timeout, ambiguity, callback handling, and recovery never create additional Payment statuses. `ACCEPTED`, `PENDING`, `TEMPORARY_FAILURE`, and `UNKNOWN` leave Payment `PROCESSING`; `UNKNOWN` enters status recovery without blind resubmission.

### Ownership

The Payment module owns the Payment lifecycle, Payment idempotency, Payment Attempt business records, relationships between Payment and Payment Attempts, and payment lifecycle events. The Payment aggregate references attempts but does not embed an unbounded attempt collection.

The Provider module owns provider adapters, adapter-specific request/response evidence, webhook receipts, retry scheduling, provider communication state, ambiguity, and status-recovery orchestration. Provider progress does not become `PaymentStatus`.

Reversal remains a separate aggregate. Ledger and Reconciliation retain their module-owned records.

### Sensitive actions

Formal maker-checker approval chains are not part of the baseline. Baseline sensitive actions use explicit tenant-scoped capability authorization, explicit confirmation, a reason where applicable, immutable audit evidence, and recent re-authentication or MFA where required.

Formal approval chains for large reversals or financial adjustments remain deferred beyond the baseline.

## Consequences

### Benefits

- Product and technical baselines now use one lifecycle vocabulary and one retry model.
- Payment cannot enter provider-, reversal-, or reconciliation-specific pseudo-states.
- Reversal retry history remains immutable without multiplying Reversal aggregates.
- Financial completion and reversal invariants have precise atomic boundaries.
- Baseline authorization remains explicit without introducing deferred approval infrastructure.

### Costs and constraints

- The authoritative DOCX files, embedded diagrams, plans, traceability, release descriptions, and future API schemas must remain synchronized with these exact enums.
- Reversal retry requires both a workflow state transition and immutable provider-attempt history.
- A future partial-reversal or formal approval-chain proposal requires a new product-baseline revision and ADR.

## Alternatives considered

### Retain separate product-facing and technical lifecycle vocabularies

Rejected because a translation layer would preserve contradictory authoritative models and introduce invalid-state mappings.

### Keep provider and reversal progress inside PaymentStatus

Rejected because it collapses independent histories, expands PaymentStatus with orchestration detail, and violates the approved product model.

### Create a new Reversal aggregate after each failed attempt

Rejected because one Reversal workflow must represent the business reversal while immutable attempts represent retries.

### Support partial or cumulative reversals

Rejected because the Product Definition approves only a complete offset of the originating Payment.

### Keep maker-checker approval in the baseline

Rejected because formal approval chains for large reversals and financial adjustments are explicitly deferred.

## Impact assessment

- Product requirements: Clarifies PAY-03, PAY-06, PAY-08, BR-13, and BR-14 without adding new baseline capability.
- Data and migration: Future schemas require exact status constraints, one Reversal workflow per Payment, immutable attempts, and duplicate-posting protection. No released Payment migration exists to alter.
- Security and tenancy: Sensitive actions remain tenant-scoped, capability-authorized, confirmed, reasoned, and audited.
- Testing: Requires exhaustive transition, terminal-state, retry-history, concurrency, ambiguity-recovery, atomic-completion, and atomic-reversal tests.
- Operations and reliability: Provider ambiguity remains recoverable while Payment stays `PROCESSING`; reversal retry does not erase prior evidence.
- Cost and delivery: Documentation reconciliation completes before Payment implementation; no later-release infrastructure is introduced.
- Documentation: Product Definition v1.2, Technical Specification v1.1, the active release plan, traceability, and repository links are updated together.

## Review conditions

Reconsider this decision only through a Product Definition revision and a new ADR if the product approves partial reversals, multiple business reversal workflows per Payment, a different terminal-state recovery model, or formal approval chains in the baseline.

## Approval

- Product owner: Approved
- Architecture owner: Approved
- Approved deviations recorded in authoritative documents: Approved through Product Definition v1.2 and Technical Specification v1.1
