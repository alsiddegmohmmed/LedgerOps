# ADR-027: Define Release 0.3 Provider scenarios, product health, operational projections, and live-update semantics

Status: Accepted  
Date: 2026-07-27  
Decision owners: Product owner; Architecture owner  
Supersedes: Unspecified Release 0.3 Provider-scenario, product-health, timeline-projection, notification-identity, and SSE details in Product/Technical v1.6  
Superseded by: None

## Context

Release 0.3 must deliver authenticated Provider-scenario control, current/recent Provider health, one understandable operational timeline, actionable dashboards, in-product notifications, reports, exports, and live updates.

Release 0.2 exposes technical metrics and specific final Payment facts, but it does not define:

- how global, Tenant, and Payment scenario configuration is versioned and pinned without changing Provider idempotency content;
- a durable product-facing Provider-health state or evaluation policy;
- a complete Payment lifecycle fact for read projections;
- stable notification and projection identities; or
- SSE replay, Tenant switching, and stale/disconnected behavior.

Leaving these choices to individual UI/reporting slices would create conflicting projections and could make Prometheus, Redis, or browser state appear authoritative.

## Decision

### Provider scenario ownership and scope

Provider owns versioned `ProviderScenarioProfile` and `ProviderScenarioAssignment` records. Platform Administration authorizes and orchestrates configuration through `provider::api`; the Provider Simulator exposes no unauthenticated administration endpoint.

An assignment scope is exactly:

```text
GLOBAL | TENANT | PAYMENT
```

Resolution precedence is:

```text
PAYMENT > TENANT > GLOBAL > deterministic default SUCCESS profile
```

At most one active assignment exists for one scope target. Updating configuration creates a new immutable profile/assignment version and supersedes the prior active assignment. It never changes historical Provider work, attempts, interactions, results, webhooks, settlement files, or Reconciliation evidence.

A profile contains these independent closed dimensions:

```text
submissionOutcome =
  SUCCESS | DECLINE | ACCEPTED | PENDING |
  TIMEOUT_AFTER_ACCEPTANCE |
  TEMPORARY_FAILURE_SAFE_TO_RESUBMIT |
  TEMPORARY_FAILURE_STATUS_RECOVERY

webhookMode =
  NORMAL | DELAYED | DUPLICATE | MISSING | INVALID_SIGNATURE | OUT_OF_ORDER

settlementMode =
  EXACT | MISSING | AMOUNT_MISMATCH | CURRENCY_MISMATCH |
  STATUS_MISMATCH | DUPLICATE_RECORD | DATE_MISMATCH
```

Profile parameters include deterministic delay values and fixture identifiers where the selected mode needs them. Unsupported combinations fail validation before activation.

The resolved profile ID, version, and canonical snapshot are pinned when the first Provider attempt for a Payment or Reversal is created. Every retry and status query for that business operation reuses the pinned snapshot. A later scenario change affects only a new Provider operation.

### Provider Simulator contract evolution

Release 0.3 adds version-2 signed Provider submission/Reversal contracts. The request body carries the pinned scenario snapshot, so the existing HMAC body hash protects it.

The Payment/Reversal business `requestIntentHash` remains unchanged because scenario configuration is not client business intent. Provider request-content hashing includes the scenario snapshot. Reusing one Provider idempotency key with a different scenario snapshot is a consistency conflict.

The Simulator durably records the scenario snapshot with the Provider transaction and uses it for response, webhook, and settlement generation. Status queries continue to use the stable Provider idempotency key.

### Product-facing Provider health

Provider computes health from Provider-owned immutable communication evidence in PostgreSQL. Prometheus exposes the same evidence operationally but is never product or authorization truth.

`ProviderHealthState` is exactly:

```text
UNKNOWN | HEALTHY | DEGRADED | UNAVAILABLE
```

A versioned `ProviderHealthPolicy` is Platform-configurable. The seeded Release 0.3 policy is:

- rolling evidence window: 5 minutes;
- evaluation interval: 30 seconds;
- minimum completed calls: 10;
- `UNKNOWN`: fewer than 10 completed calls;
- `UNAVAILABLE`: circuit is `OPEN`, or at least 10 calls exist and successful communications are zero;
- `DEGRADED`: circuit is `HALF_OPEN`, timeout/system-error rate is at least 20 percent, or p95 communication latency is at least 3 seconds;
- `HEALTHY`: none of the preceding conditions applies.

A business decline is a successful Provider communication and is not counted as an availability error. Health evidence preserves call count, successful communication count, timeout count, system-error count, p95 latency, circuit state, window boundaries, and policy version.

Provider stores append-only health evaluations and one current pointer. `ProviderHealthChanged` is emitted only when the closed state changes; it uses persisted health version and Provider ID as partition key.

### Complete lifecycle facts

Payment appends `PaymentLifecycleChanged` for every persisted Payment status transition beginning in Release 0.3. The event contains Payment ID, from/to status, persisted aggregate version, actor/automated source, reason code where applicable, correlation/causation, and occurrence time.

Deduplication is:

```text
payment-lifecycle:<paymentId>:<aggregateVersion>
```

Existing specific `PaymentCompleted` and `PaymentFailed` contracts remain unchanged for Release 0.2 compatibility. The generic lifecycle fact is for operational projections and does not create another business or financial effect.

Existing records are not given fabricated histories. Migration may create one explicit `BASELINE_IMPORTED` projection marker containing the current state and source-baseline version. New transitions append real facts.

Reversal, Tenant, Merchant, Identity, Risk, Casework, Reconciliation, Provider-health, and Provider-scenario facts remain those defined by ADR-025 and this ADR.

### Timeline and reporting projections

Reporting owns rebuildable read projections for Payment search/detail timeline, dashboards, queues, reports, exports, and live activity. Source modules and their PostgreSQL records remain authoritative.

Each projected item is unique by:

```text
projectionName + sourceMessageId
```

Projection rows preserve Tenant, Merchant where applicable, source module/type/ID, business occurrence time, actor/source, outcome, correlation, and source message ID. Duplicate delivery performs no second projection effect. Rebuild starts from authoritative source facts/events and may replace projection data without changing source state.

A Payment timeline composes the projection with focused current-detail APIs where necessary. It must not infer an unrecorded business outcome from infrastructure logs.

### In-product notification identity

Notification consumes approved lifecycle facts. A notification is unique by:

```text
recipientApplicationUserId + sourceMessageId + notificationType
```

Recipient selection uses current Identity membership, role, permission, and Merchant scope at consumer processing time. Reading or following a notification revalidates current authority; a stale notification cannot disclose a now-forbidden resource.

Target-breach scheduling is durable and uses due time carried by the source RiskReview/Case fact plus injected `Clock`. Duplicate/reordered source messages cannot create duplicate notifications.

Notification read state is mutable (`readAt`); source identity and notification content are immutable.

### SSE live-update contract

Reporting exposes Tenant-scoped SSE from persisted projection events. Each event has a monotonically increasing projection event ID within the Tenant stream. The browser reconnects with `Last-Event-ID`; the server resumes after that ID when retained or returns an explicit resync instruction when the cursor is unavailable.

SSE authorization is validated at connection and on reconnect. Changing Tenant closes the old stream, clears its client projection state, loads the new Tenant snapshot, and opens a new stream. The UI displays `LIVE`, `RECONNECTING`, or `STALE`; no live-update state becomes transactional truth.

Release 0.3 performs no automatic projection-event purge. Retention/purge policy is a Release 1.0 operational-hardening concern.

## Consequences

Positive:

- Scenario changes cannot alter an in-flight Provider idempotency identity.
- Product health is reproducible from durable evidence rather than metrics state.
- Payment transitions, projections, notifications, and SSE have stable duplicate-safe identities.
- Dashboard/timeline data remains rebuildable and cannot mutate financial truth.

Negative or costly:

- Provider contracts gain a version-2 request shape and compatibility fixtures.
- Existing Payment transition paths must append the generic lifecycle fact.
- Reporting and Notification require durable projection/scheduling tables and duplicate/rebuild tests.

## Alternatives considered

### Configure the Simulator through a mutable administration endpoint

Rejected because Core/Simulator state could diverge and historical attempts could silently observe changed behavior.

### Resolve scenario again for every retry

Rejected because retries share one Provider idempotency key and changed request content would create a conflict or inconsistent Provider behavior.

### Derive product health directly from Prometheus

Rejected because metrics are operational derivatives, not durable product truth.

### Build timelines from logs

Rejected because logs are not Tenant-authoritative, complete, or stable business evidence.

### Push live state directly from source transactions to browsers

Rejected because browser delivery cannot join source transactions and would make recovery/replay unreliable.

## Impact assessment

- Product: PRV-01, PRV-05, PAY-04, OPS-01 through OPS-04, NTF-01, AUD-03/04, DEV-03/04.
- Modules: Administration authorizes scenario changes; Provider owns scenario/health; Reporting/Notification remain derived.
- Contracts: Provider HTTP v2, `PaymentLifecycleChanged`, `ProviderScenarioChanged`, `ProviderHealthChanged`, SSE cursor/resync contract.
- Data: scenario profile/assignment/snapshot, health policy/evaluation/current pointer, projection/in-product notification/SSE event stores.
- Testing: scenario precedence/pinning/idempotency, health boundaries, event duplicate/reorder/rebuild, current authority, SSE reconnect/Tenant switch/staleness.

## Review conditions

Reconsider through a new ADR if multi-Provider routing is introduced, scenarios must change an in-flight Provider transaction, health policy needs external telemetry as authority, or projections are extracted into a separately owned service.

## Approval

- Product owner: Approved by delegated instruction on 2026-07-27
- Architecture owner: Approved
- Approved deviations recorded in authoritative documents: Product Definition v1.7 and Technical Specification v1.7
