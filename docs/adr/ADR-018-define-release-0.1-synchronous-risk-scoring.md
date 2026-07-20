# ADR-018: Define Release 0.1 synchronous Risk scoring

Status: Accepted

Date: 2026-07-20

Decision owners: Product owner; Architecture owner

Supersedes: None

Superseded by: None

## Context

Product Definition v1.3 RSK-01 and RSK-02 required configurable synchronous Risk evaluation, score contributions, three decisions, tenant-configurable thresholds, and recorded evidence. Technical Specification v1.2 assigned Risk ownership and required append-only evaluations. Neither document defined the initial rule catalog, score calculation, threshold boundaries, failure behavior, persistence uniqueness, or transaction boundary with Payment.

Those omissions prevented a safe implementation of Release 0.1 Slice 6. Choosing defaults in code would have created unsupported product and architecture decisions. Production implementation therefore remained paused while the model was reviewed and approved.

The existing Payment aggregate already provides `startValidation()`, `approve()`, `requestRiskReview()`, and `reject()`. The Payment table already has a `version` column, but its creation-only persistence port does not yet expose lifecycle compare-and-set operations. The Payment module does not yet depend on Risk. These are compatible implementation constraints, not reasons to change the approved lifecycle or ownership model.

## Decision

### Rule and profile model

Release 0.1 supports exactly one rule type: `PAYMENT_AMOUNT_THRESHOLD`. A tenant profile may contain multiple configured instances. Each rule contains `ruleId`, `profileId`, `currency`, `amountThreshold`, `scoreContribution`, and `enabled`.

- `amountThreshold` must be greater than zero.
- `scoreContribution` must be an integer from 1 through 100.
- A rule is eligible only when enabled and its currency equals the Payment currency.
- An eligible rule triggers when `paymentAmount >= amountThreshold`.
- At least one enabled rule must be eligible for the Payment currency.

Every tenant has one explicitly persisted active Risk profile version. The profile contains `profileId`, `tenantId`, `version`, `reviewThreshold`, `rejectThreshold`, `active`, and `createdAt`. Rules belong to one exact profile version. No implicit global or runtime fallback profile exists.

### Evidence and scoring

For every eligible rule, the Risk Evaluation records `ruleId`, `ruleType`, `currency`, `amountThreshold`, `configuredContribution`, `triggered`, and `appliedContribution`. `appliedContribution` is zero when the rule does not trigger.

Each evaluation records `tenantId`, `paymentId`, `profileId`, `profileVersion`, `uncappedScore`, `finalScore`, `decision`, and `evaluatedAt`. Profile identity and version make historical decisions reproducible.

`RiskScore` is an integer from 0 through 100:

```text
uncappedScore = sum(applied contributions from triggered eligible rules)
finalScore = min(100, uncappedScore)
```

The evaluation records both scores. Thresholds satisfy:

```text
1 <= reviewThreshold < rejectThreshold <= 100
```

The exact decisions are:

- `finalScore < reviewThreshold` produces `APPROVE`.
- `reviewThreshold <= finalScore < rejectThreshold` produces `MANUAL_REVIEW`.
- `finalScore >= rejectThreshold` produces `REJECT`.

Payment maps those decisions to the existing `approve()`, `requestRiskReview()`, and `reject()` transitions from `VALIDATING`. This decision adds no `PaymentStatus` value and does not change the Payment state machine.

### Failure and transaction model

Missing or multiple active profiles, invalid thresholds, malformed rules, no eligible rule, unexpected evaluation failure, and an optimistic-concurrency conflict are typed configuration or processing errors. A failure produces no partial evaluation, rule results, or Payment decision. The Payment remains `VALIDATING`. The application preserves the correlation identifier and never silently approves, rejects, or applies a fallback.

Payment owns two short PostgreSQL transactions:

1. Load a `CREATED` Payment and its version, apply `startValidation()`, update through optimistic compare-and-set, and commit.
2. Load the `VALIDATING` Payment and version, load and validate the active Risk profile and rules through the published Risk API, evaluate deterministically, persist one evaluation and all rule results, apply exactly one decision transition, update Payment through optimistic compare-and-set, and commit all effects atomically.

If transaction 2 fails, the complete transaction rolls back. The committed Payment remains `VALIDATING`.

### Uniqueness and module boundary

Release 0.1 permits one automated initial Risk Evaluation per Payment. The database enforces uniqueness equivalent to `(tenant_id, payment_id)`. No `evaluation_kind` abstraction is introduced. Concurrent attempts create one evaluation, one set of rule results, and one Payment transition. A repeat returns the existing result or an explicit already-evaluated outcome.

Risk owns profiles, profile versions, rules, scoring, decisions, evaluations, and evaluated-rule results. Payment owns its lifecycle, persistence, and cross-module orchestration. Payment calls a published `risk::api` interface using neutral request values and receives profile, score, decision, and evaluation identifiers.

Risk does not depend on Payment domain classes, access Payment tables, or mutate Payment. Payment does not access Risk tables or persist Risk records. No cross-schema foreign key links Risk and Payment.

Release 0.1 may provision profiles through migrations, seed data, tests, or an internal application use case. Merchant-facing configuration management, configuration-change auditing, manual-review queues, human Risk decisions, Kafka, and asynchronous Risk processing remain outside this slice.

## Consequences

Positive:

- Slice 6 has deterministic, testable rule, score, threshold, evidence, transaction, and concurrency semantics.
- Exact profile versions make historical decisions reproducible.
- Failure cannot produce an unrecorded or partially recorded Payment decision.
- Module ownership remains enforceable through Spring Modulith and schema boundaries.

Negative or costly:

- Release 0.1 demonstrates only amount-threshold Risk behavior.
- Every supported Payment currency requires at least one enabled eligible rule.
- Payment orchestration needs a new lifecycle persistence port with optimistic compare-and-set behavior.
- Cross-module atomicity couples the Risk and Payment writes to the shared PostgreSQL transaction, which is intentional for Release 0.1.

## Alternatives considered

### Apply an implicit default profile

Rejected because a hidden fallback makes decisions irreproducible and can silently approve or reject a Payment without tenant-owned configuration.

### Approve when configuration is missing or invalid

Rejected because fail-open behavior bypasses an explicit pre-provider control.

### Reject when configuration is missing or invalid

Rejected because configuration failure is not a supported business rejection and must not create a false terminal Payment outcome.

### Use one transaction for validation start and Risk evaluation

Rejected because the approved model requires a durable `VALIDATING` state when evaluation cannot finish and separates validation-start failure from Risk-processing failure.

### Use pessimistic locking

Rejected for this slice because repository inspection found no demonstrated contention problem that optimistic compare-and-set and uniqueness constraints cannot handle. Reconsider locking only with measured evidence.

### Add `evaluation_kind` for future evaluations

Rejected because Release 0.1 supports one automated initial evaluation per Payment. A speculative discriminator would expand scope without current behavior.

### Add more Risk rule types

Rejected because Release 0.1 authorizes only `PAYMENT_AMOUNT_THRESHOLD`. Additional rules require an approved product and architecture change.

## Impact assessment

- Product requirements: Clarifies Product Definition §7.4, RSK-01, RSK-02, the Risk assessment terminology, failure behavior, and Release 0.1 versus later Risk scope.
- Data and migration: Future Risk migrations require versioned profiles, profile-owned rules, append-only evaluations and rule results, one active profile per tenant, and evaluation uniqueness equivalent to `(tenant_id, payment_id)`. No cross-schema foreign key is permitted.
- Security and tenancy: Every profile and evaluation is tenant-owned. Authorization and merchant administration remain scheduled outside Release 0.1.
- Testing: Requires exact domain, application, PostgreSQL/Testcontainers, rollback, optimistic-concurrency, coordinated-concurrency, repeat, migration, and Modulith evidence listed in Technical Specification v1.3 §5.8.6.
- Operations and reliability: Typed errors preserve correlation identifiers; failed evaluation leaves Payment `VALIDATING` with no partial Risk evidence.
- Cost and delivery: Adds a focused Risk module and Payment orchestration slice without later-release infrastructure.
- Documentation: Product Definition v1.4, Technical Specification v1.3, the active plan, traceability, README, and AGENTS.md are aligned.

## Affected Product Definition sections

- Document control and revision history
- §5 Core product model and terminology
- §7.4 Risk evaluation and manual review, including RSK-01 and RSK-02
- §11 Failure, exception, and recovery behavior
- §13.1 Product delivery phases
- §14 Product baseline decision

## Affected Technical Specification sections

- Document control and revision history
- §4.3 Module ownership
- §5.1 Primary aggregates
- §5.2 Core value objects
- §5.8 Release 0.1 synchronous Risk model
- §13.1 Test layers
- §13.2 Critical verification matrix
- §13.4 Release-blocking defects
- §14.1 Release 0.1 - Transactional Core
- Implementation authorization

## Review conditions

Reconsider this decision through a Product Definition revision and a new or superseding ADR if LedgerOps adds a Risk rule type, multiple automated evaluations per Payment, a different score range or aggregation method, asynchronous Risk evaluation, merchant-facing configuration management, or measured contention that optimistic concurrency cannot handle safely.

## Approval

- Product owner: Approved
- Architecture owner: Approved
- Approved deviations recorded in authoritative documents: Product Definition v1.4 and Technical Specification v1.3
- Implementation status at approval: Paused pending reconciliation; implementation remains pending review of these controlled revisions
