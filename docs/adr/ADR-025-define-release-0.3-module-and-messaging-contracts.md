# ADR-025: Define Release 0.3 module dependency and messaging contracts

Status: Accepted  
Date: 2026-07-27  
Decision owners: Product owner; Architecture owner  
Supersedes: Release 0.2-only module and producer catalogue in ADR-021 for Release 0.3 additions  
Superseded by: None

## Context

Release 0.3 adds Identity, Audit, Casework, Reconciliation, Notification, Reporting, Reversal contracts, Risk operations, and Tenant/Merchant lifecycle events. ADR-021 intentionally closed Release 0.2 producer names to `payment` and `provider` and did not define these dependencies or messages.

Implementing without a locked graph and catalogue would create module cycles, ad-hoc topics, or unstable outbox identities.

## Decision

### Acyclic module direction

The Release 0.3 compile-time/application dependency direction is:

```text
messaging  -> no business module
audit      -> no business module
identity   -> audit::api, messaging::api
ledger     -> identity::api

tenancy   -> identity::api, audit::api, messaging::api
merchant   -> tenancy::api, identity::api, audit::api, messaging::api
customer   -> tenancy::api, merchant::api
risk       -> identity::api, audit::api, messaging::api
provider   -> identity::api, audit::api, messaging::api
administration -> identity::api, tenancy::api, merchant::api,
                  risk::api, provider::api, audit::api, messaging::api
payment    -> tenancy::api, merchant::api, customer::api,
              identity::api, risk::api, provider::api, ledger::api,
              audit::api, messaging::api
reconciliation -> identity::api, payment::api, provider::api,
                  ledger::api, audit::api, messaging::api
casework   -> identity::api, payment::api, reconciliation::api,
              ledger::api, audit::api, messaging::api
notification -> identity::api, messaging::api
reporting  -> identity::api, payment::api, risk::api, provider::api,
              ledger::api, reconciliation::api, casework::api,
              audit::api, messaging::api
```

No reverse dependency is permitted.

In particular:

- Identity has no business-module dependency.
- Administration owns cross-module Platform/Tenant administration and stores no duplicate business truth.
- Payment does not depend on Casework or Reconciliation.
- Reconciliation does not depend on Casework.
- Casework may call focused Payment/Reconciliation/Ledger published APIs.
- Notification and Reporting cannot mutate source modules.
- Audit and Messaging remain generic and know no business semantics.
- Ledger depends on Identity only for its protected query application boundary; financial posting APIs still enforce caller-owned business templates and expose no arbitrary journal operation.
- Reporting dependencies are read-only published APIs plus event consumers.

### Case creation and Risk escalation without cycles

Risk/Payment and Reconciliation request Cases through `ledgerops.casework.commands.v1`, not a direct Casework dependency.

The source module generates and persists a stable `caseId`, appends `CreateCaseRequested`, and commits the source decision plus outbox once. Casework consumes idempotently and enforces one Case per source identity.

Manual Risk `ESCALATE` is the final decision for the RiskReview. Payment owns the cross-module decision transaction, calls `risk::api`, leaves Payment `RISK_REVIEW`, allocates one stable case ID, and appends one stable `CreateCaseRequested` command. Risk still owns the RiskReview and decision evidence.

Case resolution category `RISK_APPROVE` or `RISK_REJECT` is applied by a Casework-owned transaction that calls a focused `payment::api` operation. Payment validates the linked RiskReview/Case evidence and performs only `RISK_REVIEW -> APPROVED | REJECTED`. Case resolution, Payment transition, audit, and lifecycle outbox commit once.

Payment never queries Casework tables.

### Producer catalogue

The closed Release 0.3 producer names are:

```text
payment
provider
tenancy
merchant
identity
risk
reconciliation
casework
```

Adding another producer requires an ADR or approved Technical revision.

### Topic catalogue

Existing topics remain:

```text
ledgerops.provider.commands.v1
ledgerops.provider.results.v1
ledgerops.payment.commands.v1
ledgerops.payment.lifecycle.v1
```

Release 0.3 adds:

```text
ledgerops.tenancy.lifecycle.v1
ledgerops.identity.lifecycle.v1
ledgerops.provider.lifecycle.v1
ledgerops.risk.lifecycle.v1
ledgerops.casework.commands.v1
ledgerops.casework.lifecycle.v1
ledgerops.reconciliation.lifecycle.v1
```

Notification and Reporting consume these facts. They do not receive source-table access. `ProviderScenarioChanged` carries scope, target, immutable profile/version, and effective-from time. `ProviderHealthChanged` carries closed state `UNKNOWN | HEALTHY | DEGRADED | UNAVAILABLE`, policy version, evidence window, and persisted health version.

### Partition keys

- Payment/Reversal Provider and lifecycle messages: originating Payment ID.
- Provider scenario and health lifecycle: Provider ID.
- Tenant/Merchant lifecycle: Tenant ID.
- Identity lifecycle: Tenant ID for Tenant-owned identity records; application user ID for platform-only records.
- Risk lifecycle: Payment ID for review/decision; Tenant ID for configuration.
- Case command/lifecycle: Case ID.
- Reconciliation lifecycle: batch family ID for batch/run/current-run events; settlement posting ID for posting events.

Ordering is guaranteed only within one partition key.

### Message catalogue and outbox identity

All messages use the ADR-021 envelope and JSON Schema/version rules.

| Producer | Message type | Topic | Deduplication key |
|---|---|---|---|
| payment | `PaymentLifecycleChanged` | payment lifecycle | `payment-lifecycle:<paymentId>:<aggregateVersion>` |
| payment | `SubmitReversalToProvider` | provider commands | `reversal-submission:<attemptId>` |
| provider | `ProviderReversalResultObserved` | provider results | `provider-result:<tenantId>:<providerId>:<providerResultId>` |
| payment | `ReversalRequested` / `ReversalProcessingStarted` / `ReversalFailed` / `ReversalCompleted` | payment lifecycle | `reversal-lifecycle:<reversalId>:<aggregateVersion>` |
| payment | `CreateCaseRequested` for Risk escalation | casework commands | `case-request:RISK_REVIEW:<riskReviewId>` |
| reconciliation | `CreateCaseRequested` for discrepancy | casework commands | `case-request:RECONCILIATION_DISCREPANCY:<discrepancyId>` |
| tenancy | `TenantLifecycleChanged` | tenancy lifecycle | `tenant-event:<tenantId>:<version>` |
| merchant | `MerchantLifecycleChanged` | tenancy lifecycle | `merchant-event:<merchantId>:<version>` |
| identity | `IdentityLifecycleChanged` | identity lifecycle | `identity-event:<aggregateType>:<aggregateId>:<version>` |
| provider | `ProviderScenarioChanged` | provider lifecycle | `provider-scenario:<assignmentId>:<version>` |
| provider | `ProviderHealthChanged` | provider lifecycle | `provider-health:<providerId>:<healthVersion>` |
| risk | `RiskLifecycleChanged` | risk lifecycle | `risk-event:<aggregateType>:<aggregateId>:<version>` |
| casework | `CaseLifecycleChanged` | casework lifecycle | `case-event:<caseId>:<eventSequence>` |
| reconciliation | `ReconciliationLifecycleChanged` | reconciliation lifecycle | `reconciliation-event:<aggregateType>:<aggregateId>:<versionOrSequence>` |

The Reversal lifecycle messages use the persisted Reversal aggregate version, so repeated `FAILED -> PROCESSING -> FAILED` cycles and later `COMPLETED` each have a distinct stable identity. `FAILED` is not treated as a terminal identity. Each payload contains the causative attempt/evidence where applicable. `ReversalFailed` is a retryable lifecycle fact, not a final business identity.

Lifecycle event sequence/version is persisted with the aggregate in the same transaction. It is not generated anew during publication retry.

### Consumer identities

Consumer names are versioned and closed per contract. Minimum Release 0.3 consumers:

```text
provider-reversal-command-consumer-v1
payment-provider-reversal-result-consumer-v1
casework-create-command-consumer-v1
notification-tenancy-consumer-v1
notification-identity-consumer-v1
notification-provider-health-consumer-v1
notification-risk-consumer-v1
notification-casework-consumer-v1
notification-reconciliation-consumer-v1
reporting-tenancy-consumer-v1
reporting-merchant-consumer-v1
reporting-payment-consumer-v1
reporting-provider-consumer-v1
reporting-identity-consumer-v1
reporting-risk-consumer-v1
reporting-casework-consumer-v1
reporting-reconciliation-consumer-v1
```

A consumer is added only with a concrete slice contract and exact inbox identity `consumerName + messageId`.

### Transaction rules

Business state, audit evidence where required, and outbound intent commit once through the existing Messaging API.

Kafka publication remains outside the business transaction and at least once. Consumer effects remain inbox-backed and idempotent.

Direct published APIs are used only for same-database strong-consistency boundaries. Events/commands are used where a direct dependency would create a cycle or where eventual derived behavior is appropriate.

## Consequences

Positive:

- Spring Modulith dependencies remain acyclic.
- New messages have stable identities before implementation.
- Case creation and Risk resolution preserve module ownership without a saga for the Payment transition.
- Notification/Reporting remain derived.

Negative or costly:

- ProducerName, schemas, topic provisioning, compatibility fixtures, and architecture tests expand.
- More explicit contracts are required before each slice is complete.

## Alternatives considered

### One generic business-events topic

Rejected because it weakens ownership, partitioning, schema evolution, and operational visibility.

### Direct bidirectional module calls

Rejected because it creates compile-time cycles and hidden transactional coupling.

### Let each slice invent producer names

Rejected because ADR-021 intentionally uses a closed catalogue and exact business identity.

## Impact assessment

- Code: ProducerName expansion, schemas, consumers, module declarations, ArchUnit/Modulith tests.
- Data: existing generic outbox/inbox schema remains; no business tables move.
- Operations: new topics, lag/poison/dead-letter dashboards and runbooks.
- Testing: dependency graph, dedup keys, partition keys, duplicate delivery, poison handling, compatibility, and no cross-table access.

## Review conditions

Reconsider through a new ADR if a module is extracted, a new producer/topic is required, or a direct strong-consistency boundary cannot be represented by the approved graph.

## Approval

- Product owner: Approved by delegated instruction on 2026-07-27
- Architecture owner: Approved
- Approved deviations recorded in authoritative documents: Technical Specification v1.7
