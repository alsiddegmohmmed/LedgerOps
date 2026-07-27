# ADR-014: Use compensating transactions instead of Ledger mutation

Status: Accepted - retrospective repository record  
Decision date: 2026-07-13  
Repository record date: 2026-07-27  
Decision owners: Product owner; Architecture owner  
Supersedes: None  
Superseded by: None

## Provenance

Technical Design and Architecture Specification v1.0 approved this decision on 13 July 2026. This file restores repository traceability. It is not a recovered original and introduces no new architecture.

## Context

Financial history must remain reconstructable. Editing or deleting a posted transaction would erase the original event, weaken audit evidence, and make concurrent replay and reconciliation unsafe.

## Decision

Posted Ledger transactions and entries are immutable.

- A correction creates a new balanced transaction.
- The new transaction references the original transaction it compensates.
- The original remains readable and unchanged.
- Compensation must use the same Tenant and valid currency/account relationships.
- A transaction cannot compensate itself.
- Source identity and exact replay prevent duplicate compensation.
- Operators cannot perform arbitrary journal administration.

ADR-023 defines the full-Reversal compensation. ADR-024 defines the narrow Release 0.3 controlled settlement correction.

## Consequences

Positive:

- Financial history and audit evidence remain complete.
- Replays and investigations can validate the original and correction independently.
- Database constraints can prevent duplicate or invalid compensation.

Negative or costly:

- Current balances require considering original and compensating entries.
- Correction workflows need explicit reason, authorization, source identity, and replay rules.
- Invalid source-specific postings cannot be silently normalized.

## Alternatives considered

### Update the original entries

Rejected because it destroys historical truth.

### Delete and recreate the posting

Rejected because it creates audit gaps and race conditions.

### Allow a generic manual journal UI

Rejected because it bypasses source-specific business invariants.

## Impact assessment

- Product requirements: LED-03, LED-04, BR-03, Reversal and correction workflows.
- Data: append-only transaction/entry records and explicit compensation reference.
- Security: compensation is capability-authorized, confirmed, reasoned, and audited.
- Testing: immutability, balance, same-Tenant linkage, duplicate compensation, replay, concurrency, and rollback.
- Documentation: Technical Specification, ADR-023, ADR-024, API contracts, and runbooks.

## Review conditions

Reconsider only if the financial model itself changes through an approved Product revision and superseding ADR.

## Approval

- Product owner: Approved in Technical Specification v1.0
- Architecture owner: Approved in Technical Specification v1.0
- Retrospective record introduces a new decision: No
