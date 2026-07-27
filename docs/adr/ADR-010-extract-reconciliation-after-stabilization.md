# ADR-010: Extract Reconciliation only after ownership and contracts stabilize

Status: Accepted - retrospective repository record  
Decision date: 2026-07-13  
Repository record date: 2026-07-27  
Decision owners: Product owner; Architecture owner  
Supersedes: None  
Superseded by: None

## Provenance

Technical Design and Architecture Specification v1.0 approved this decision on 13 July 2026. This file restores repository traceability. It is not a recovered original and introduces no new architecture.

## Context

Reconciliation is batch-oriented and may eventually justify independent scaling. Extracting it before settlement data, matching rules, ownership, correction requests, and contracts stabilize would create a premature distributed consistency problem and slow completion of the core product.

## Decision

Implement Reconciliation as an explicit internal Core module for Release 0.3.

- Reconciliation owns settlement batches, normalized records, immutable snapshots, runs, results, discrepancies, and current-run designation.
- It accesses Payment, Reversal, Provider, and Ledger facts only through published bounded APIs or versioned contracts.
- It never reads or mutates another module's tables.
- It detects and requests recovery/correction; Payment, Ledger, and Casework own their state changes.
- Its data model and APIs must remain extraction-ready, but no service split occurs in Release 0.3.
- Extraction requires a later ADR covering data migration, contracts, consistency, replay, cutover, failure modes, and operations.

ADR-024 defines Release 0.3 settlement posting and controlled correction semantics.

## Consequences

Positive:

- Release 0.3 can implement and test Reconciliation without premature distribution.
- Ownership and contracts are learned from real workflows before extraction.
- PostgreSQL/Testcontainers can prove deterministic, restartable batch behaviour.

Negative or costly:

- Reconciliation shares the Core deployment initially.
- Future extraction requires deliberate data and contract migration.
- Module boundaries must be enforced now to avoid hidden coupling.

## Alternatives considered

### Separate Reconciliation service immediately

Rejected because contracts and ownership were not yet stable and the added failure modes do not solve a current problem.

### Let Reconciliation query Core tables directly

Rejected because it prevents safe extraction and violates module ownership.

## Impact assessment

- Product requirements: REC-01 through REC-05; CAS-01 through CAS-05.
- Architecture: internal module with published APIs and no cross-table access.
- Testing: batch restart, deterministic snapshots/matching, module boundaries, and future contract fixtures.
- Operations: one Core deployment in Release 0.3; extraction remains future work.

## Review conditions

Reconsider after Release 1.0 only when workload, team ownership, deployment independence, or measured scaling justifies extraction.

## Approval

- Product owner: Approved in Technical Specification v1.0
- Architecture owner: Approved in Technical Specification v1.0
- Retrospective record introduces a new decision: No
