# ADR-021: Define Release 0.2 provider and messaging semantics

Status: Accepted
Date: 2026-07-21
Decision owners: Product owner; Architecture owner
Supersedes: None
Superseded by: None

## Summary

Release 0.2 surrounds the accepted Release 0.1 Payment-success completion boundary with durable Payment Attempts, provider evidence, at-least-once Kafka delivery, transactional outbox records, consumer inbox records, recoverable provider work, signed provider HTTP contracts, ambiguity recovery, and operational telemetry.

This ADR authorizes Release 0.2 implementation after completion of Slice 0. Implementation must follow the accepted Technical Specification v1.6 and active Release 0.2 plan.

## Context

Product Definition v1.6 requires immutable provider-attempt history, duplicate-safe provider outcomes and callbacks, safe retry controls, provider health evidence, and explicit behavior for timeouts, temporary errors, duplicate webhooks, out-of-order webhooks, missing webhooks, and invalid signatures. Technical Specification v1.6 selects Kafka, transactional outbox, consumer inbox, at-least-once delivery, JSON Schema, a separate Provider Simulator, Resilience4j, OpenTelemetry, Prometheus, and Grafana for Release 0.2.

The current repository completes Release 0.1 but contains none of those Release 0.2 capabilities. It has one root Gradle/Spring Boot application, six Spring Modulith modules, PostgreSQL migrations V1 through V7, PostgreSQL-only Testcontainers support, and the accepted ADR-020 completion boundary. `CompletePaymentAfterProviderSuccess` locks a tenant-scoped Payment row, validates the complete Ledger replay shape through `ledger::api`, posts one `PAYMENT`-source transaction, changes Payment from `PROCESSING` to `COMPLETED`, and commits both effects in one PostgreSQL transaction.

Release 0.2 must add distributed delivery without moving, duplicating, or weakening that boundary. ADR-021 and Technical Specification v1.6 resolve message-store ownership, provider-work durability, exact retry limits, claim leases, HMAC canonicalization, webhook deduplication, final-result replay, and the distinction between atomic outbox insertion and later Kafka publication.

### Authority

- Product Definition v1.6: §§5.1, 6.4, 7.3, 7.5, 7.12, 8.1–8.2, 10–12; PAY-03, PAY-04; PRV-01 through PRV-05; DEV-01 through DEV-04; BR-01, BR-05, BR-06, BR-12, and BR-13.
- Technical Specification v1.6: §§1.1–1.2, 3.1–3.3, 4.1–4.4, 5.1, 5.3, 5.6–5.7, 6.1–6.5, 7.1–7.8, 8.1, 8.6, 9.1–9.6, 11.1–11.6, 12.1–12.3, 13, 14.2, 15–17, and Appendix C.
- Accepted retrospective ADR-004, ADR-005, ADR-006, and ADR-009 records, plus accepted ADR-016 and ADR-020.

Slice 0 adds retrospective ADR-004, ADR-005, ADR-006, and ADR-009 repository records. Each file identifies Technical Specification v1.0 as its authority, states that the original decision was approved on 13 July 2026, and introduces no new decision.

## Decision

### Preserve the Payment-success boundary

Release 0.2 does not rewrite, duplicate, or expose `CompletePaymentAfterProviderSuccess` as a public endpoint.

A definitive provider `SUCCESS` reaches that existing Payment-owned use case from the Payment result consumer transaction. The existing use case continues to:

- lock the tenant-scoped Payment row;
- validate the complete Payment/posting state matrix;
- call only `ledger::api`;
- require the exact ADR-020 two-entry posting;
- preserve `UNIQUE (tenant_id, source_type, source_id)`;
- roll back Payment, Ledger transaction, and Ledger entries together; and
- return the original logical result only after exact replay verification.

The outer Payment result application transaction adds the consumer inbox record, accepted final-result evidence, and Payment lifecycle outbox record. The nested completion use case joins that transaction with the existing Spring `REQUIRED` behavior. Neither Ledger nor messaging uses `REQUIRES_NEW`.

### Module and schema ownership

The Core Platform adds two Spring Modulith modules:

- `messaging` owns generic outbox, inbox, dead-letter, claim, and publisher-lease records in the `messaging` schema. It knows no Payment, Provider, Ledger, Risk, or Reversal semantics.
- `provider` owns provider adapters, provider work and recovery state, immutable interaction and result evidence, webhook receipts, retry scheduling, ambiguity recovery, and provider communication metrics in the `provider` schema.

Payment continues to own:

- Payment lifecycle and persistence;
- Payment idempotency;
- immutable Payment Attempt business records;
- Payment-to-attempt relationships;
- one immutable accepted-final-result application record per Payment; and
- Payment lifecycle events.

The accepted-final-result application record is Payment-owned evidence that identifies the Provider evidence used for the terminal transition. It is not a new Payment status or Provider evidence record. It contains `tenantId`, `paymentId`, `attemptId`, `providerEvidenceId`, `providerResultId`, final category, provider reference when present, and `appliedAt`, with uniqueness on `(tenant_id, payment_id)`.

Business modules append messages through `messaging::api` inside their existing PostgreSQL transactions. Payment reads Provider evidence only through a neutral `provider::api` verification operation. No module queries another module's tables, and no cross-schema foreign key is added.

The allowed Release 0.2 dependency direction is exactly:

```text
Provider -> messaging::api
Payment  -> provider::api
Payment  -> messaging::api
```

Provider must not depend on `payment::api`. Provider resolves tenant, Payment, and Payment Attempt identity from Provider-owned mappings persisted when it consumes `SubmitPaymentToProvider`. Webhook reception and processing use those mappings and never query Payment.

### Payment Attempt model

Release 0.2 implements Payment-associated attempts only. Reversal attempts remain Release 0.3 work.

Each immutable Payment Attempt contains at least:

- `attemptId`;
- `tenantId`;
- `paymentId`;
- positive `sequence`, starting at 1;
- `providerId`;
- `providerIdempotencyKey`;
- `initiatedAt`;
- immutable request-intent fields: `merchantId`, `customerId`, amount, currency, and payment-method category; and
- `requestIntentHash`, calculated from the canonical request-intent fields.

Release 0.2 supports exactly one Provider:

```text
providerId = SIMULATOR
```

Every initial Payment Attempt and automatic retry uses `SIMULATOR`. Release 0.2 has no Provider router, Provider failover, Merchant-selectable Provider configuration, or multi-Provider policy. Adding any of those capabilities requires a later approved decision.

`requestIntentHash` is SHA-256 over canonical JSON containing exactly these fields in this order:

```text
providerId
merchantId
customerId
normalized amount
currency
paymentMethodCategory
```

The canonical object uses the displayed property order and UTF-8 JSON encoding. `normalized amount` is the Payment `Money` amount after currency-defined precision validation, serialized as a JSON decimal string using `BigDecimal.toPlainString()`. Contract fixtures must fix escaping and byte-for-byte serialization. No other field contributes to this hash.

The Payment aggregate does not embed an attempt collection. Attempts are queried separately by tenant and Payment.

PostgreSQL must enforce:

```text
UNIQUE (tenant_id, payment_id, sequence)
```

The provider idempotency key is exactly:

```text
payment:<lowercase canonical Payment UUID>
```

Every attempt for one Payment reuses that key. Each intentional submission or policy-permitted safe retry creates a new Payment Attempt. Duplicate Kafka delivery and low-level connection retry before request transmission do not create another attempt.

### Atomic initial submission

Payment owns one short transaction that:

1. locks the tenant-scoped Payment row;
2. requires `APPROVED`;
3. creates sequence-1 Payment Attempt evidence;
4. applies `Payment.startProcessing()`;
5. updates Payment with its expected version;
6. appends one `SubmitPaymentToProvider` schema-version-1 command through `messaging::api`; and
7. commits once.

A repeat that finds `PROCESSING` with the exact attempt and command returns the existing logical result. `APPROVED` with partial submission evidence, or `PROCESSING` with missing or mismatched evidence, is a typed critical consistency error. Every other Payment status is a typed lifecycle error.

Any failure rolls back the attempt, Payment transition, and outbox record.

### Safe retry creation

Release 0.2 supports automatic safe retries only. Public or operator-triggered replay remains excluded until Release 0.3 supplies authorization and audit controls.

Provider requests a retry by appending the version-1 `PaymentSubmissionRetryRequested` command through `messaging::api`. The command is published to `ledgerops.payment.commands.v1` with the Payment ID as its partition key. Its payload contains `retryRequestId`, `paymentId`, `previousAttemptId`, `providerEvidenceId`, `providerId`, and `requestedAt`; `tenantId` is carried by the envelope.

Payment consumes the command in one inbox-backed transaction. Payment verifies the referenced retry evidence through `provider::api`, locks the tenant-scoped Payment row, requires `PROCESSING`, validates the previous immutable attempt, allocates the next positive sequence, creates one immutable Payment Attempt, and appends one `SubmitPaymentToProvider` command. The inbox record, attempt, and outbox command commit once.

The logical retry identity is `retryRequestId`. Payment persists one retry-application record unique by `(tenant_id, retry_request_id)` and links it to the created attempt and command outbox record. Repeated delivery of the same `retryRequestId` returns the existing attempt and command. Changed retry content under the same identity is a typed consistency error. Provider never calls `payment::api`, queries Payment, or inserts Payment Attempts directly.

### Messaging model

Release 0.2 uses a dedicated internal messaging module and schema rather than one outbox implementation per business module.

#### Message envelope

Every JSON envelope contains exactly these required top-level fields:

```text
messageId
messageType
schemaVersion
aggregateId
tenantId
correlationId
causationId
occurredAt
payload
```

The envelope permits no additional top-level fields. `messageId`, `aggregateId`, `tenantId`, `correlationId`, and `causationId` are canonical lowercase UUID strings. `schemaVersion` is the integer `1` for these contracts. `messageType` is one of the exact names defined below. `occurredAt` comes from an injected `Clock` and is serialized as an RFC 3339 UTC instant. `payload` is a JSON object validated against the named schema.

`messageId` remains unchanged across publication retries. A new business command or fact receives a new `messageId`. `correlationId` identifies the end-to-end operation. `causationId` identifies the message, webhook receipt, or root application operation that caused the new message. For a root operation with no preceding message, `causationId` is the UUID of the application operation that created the first command.

The outbox also stores the Kafka topic, Payment-ID partition key, and bounded headers. Kafka headers carry `messageId`, `messageType`, `schemaVersion`, `tenantId`, `correlationId`, `causationId`, `traceparent`, and `tracestate` when present.

#### Version-1 contracts

The following contracts use JSON Schema. JPA entities, domain aggregates, and shared mutable Java classes are prohibited as contracts.

- `SubmitPaymentToProvider`, schema version 1: `attemptId`, `paymentId`, `attemptSequence`, `providerId`, `providerIdempotencyKey`, amount as a decimal string, three-letter currency, payment-method category, and `requestIntentHash`.
- `ProviderResultObserved`, schema version 1: `providerEvidenceId`, stable `providerResultId`, `attemptId`, `paymentId`, `providerId`, `providerIdempotencyKey`, exact `ProviderResultCategory`, exact `RetryDisposition`, provider reference when present, evidence origin (`SUBMISSION_RESPONSE`, `STATUS_QUERY`, or `WEBHOOK`), and `observedAt`.
- `PaymentSubmissionRetryRequested`, schema version 1: `retryRequestId`, `paymentId`, `previousAttemptId`, `providerEvidenceId`, `providerId`, and `requestedAt`. The envelope supplies `tenantId`.
- `PaymentCompleted`, schema version 1: `paymentId`, `attemptId`, `providerEvidenceId`, provider reference when present, `ledgerTransactionId`, and `completedAt`.
- `PaymentFailed`, schema version 1: `paymentId`, `attemptId`, `providerEvidenceId`, final category (`DECLINED` or `PERMANENT_FAILURE`), provider reference when present, and `failedAt`.
- `ProviderWebhook`, HTTP schema version 1: `providerEventId`, stable `providerResultId`, `providerIdempotencyKey`, provider reference when present, exact result category, provider event time, and provider-specific evidence fields allowed by the schema.

Consumers must ignore unknown optional payload properties and reject a missing or semantically changed required property. Compatibility tests must prove that a schema change described as backward compatible can still be consumed by the preceding version's consumer fixtures.

No Kafka message is required between webhook reception and processing because both stages belong to Provider and share its PostgreSQL schema. A Provider-owned durable work item schedules asynchronous webhook processing. If later extraction requires a Kafka command, it requires a new schema and compatibility review.

#### Topics and partition keys

Release 0.2 uses the existing business-capability topics:

```text
ledgerops.provider.commands.v1
ledgerops.provider.results.v1
ledgerops.payment.commands.v1
ledgerops.payment.lifecycle.v1
```

`SubmitPaymentToProvider`, `ProviderResultObserved`, `PaymentSubmissionRetryRequested`, `PaymentCompleted`, and `PaymentFailed` use the Payment ID as the partition key. Ordering is guaranteed only within one Payment partition key.

### Transactional outbox

An outbox record has one of these states:

```text
PENDING | CLAIMED | RETRYABLE | PUBLISHED | DEAD
```

The outbox persists the complete envelope, topic, partition key, bounded headers, producer name, producer-supplied deduplication key, content hash, attempt count, next-attempt time, `leaseOwner`, `leaseToken`, `leaseExpiresAt`, publication time, and a sanitized last-error code and summary.

PostgreSQL enforces business-level outbox uniqueness:

```text
UNIQUE (producer_name, deduplication_key)
```

Release 0.2 supports exactly two business producer names:

```text
payment
provider
```

The published `messaging::api` exposes a typed `ProducerName` or an equivalent closed value. It does not accept an unrestricted caller-provided string.

| Producer name | Deduplication key | Message type |
|---|---|---|
| `payment` | `payment-submission:<attemptId>` | `SubmitPaymentToProvider` |
| `payment` | `payment-final:<paymentId>` | `PaymentCompleted` or `PaymentFailed` |
| `provider` | `provider-result:<tenantId>:<providerId>:<providerResultId>` | `ProviderResultObserved` |
| `provider` | `payment-retry:<retryRequestId>` | `PaymentSubmissionRetryRequested` |

`PaymentCompleted` and `PaymentFailed` share producer name `payment` and the same `payment-final:<paymentId>` identity. Different terminal content under that identity raises a typed consistency error.

The deduplication content hash is SHA-256 over canonical JSON containing exactly:

```text
messageType
schemaVersion
aggregateId
tenantId
topic
partitionKey
payload
```

The hash excludes `messageId`, `occurredAt`, `correlationId`, `causationId`, `traceparent`, `tracestate`, delivery state, leases, and retry metadata. Equivalent business content under the same `producerName` and `deduplicationKey` returns the existing outbox record and original `messageId`. Different canonical business content raises a typed consistency error.

The canonical object uses the displayed property order and UTF-8 JSON encoding. The versioned payload schema defines payload canonicalization, and contract fixtures must fix escaping, number representation, nested-property ordering, and byte-for-byte serialization.

Publishers:

1. claim due `PENDING`, due `RETRYABLE`, or `CLAIMED` rows whose lease has expired in a short PostgreSQL transaction using `FOR UPDATE SKIP LOCKED`;
2. set `CLAIMED`, assign `leaseOwner`, assign a new `leaseToken`, and set `leaseExpiresAt`;
3. commit the claim;
4. publish to Kafka outside a database transaction; and
5. mark `PUBLISHED`, `RETRYABLE`, or `DEAD` in a later short transaction only when the supplied `leaseToken` is still current.

The claim lease is 30 seconds. A publisher may renew it every 10 seconds while actively publishing, but lease renewal and every mutable completion or update require the current `leaseToken`. Reclaiming an expired row assigns a new token. A stale publisher cannot renew or mark the row `PUBLISHED`, `RETRYABLE`, or `DEAD`. A stale publisher might already have published to Kafka, so downstream duplicate handling remains mandatory. Kafka producers use `acks=all` and idempotent producer mode, but LedgerOps still claims only at-least-once end-to-end delivery.

Outbox publication uses at most 10 attempts. Retry delay starts at 1 second, doubles to a maximum of 60 seconds, and applies deterministic jitter of plus or minus 20 percent. The jitter value is derived from `messageId` and attempt count so Clock-based tests are reproducible. Attempt 10 failure changes the outbox record to `DEAD` and creates one publication dead-letter record unique by `outboxId`, but only if the completing publisher still holds the current `leaseToken`.

Kafka acknowledgement followed by a publisher crash before the `PUBLISHED` update is an expected duplicate-publication case. Consumers, not the publisher, provide business-effect deduplication.

### Consumer inbox and dead letters

Consumer inbox uniqueness is exactly:

```text
consumerName + messageId
```

The persisted inbox status is exactly:

```text
PROCESSED
DEAD
```

The planned consumers are named `provider-command-consumer-v1`, `payment-provider-result-consumer-v1`, and `payment-retry-command-consumer-v1`.

For a valid envelope with a valid `messageId`, each consumer starts one short PostgreSQL transaction, inserts the inbox record, performs its database business effect, and commits both together. Inbox, consumer-failure, and consumer dead-letter identity are all `(consumer_name, message_id)`. If the inbox identity already exists, the consumer acknowledges the Kafka record without repeating the business effect. If business processing fails, the transaction rolls back, including the inbox insertion, so Kafka can redeliver.

Messaging owns a consumer-failure record unique by:

```text
UNIQUE (consumer_name, message_id)
```

The record stores `failureCount`, `firstFailedAt`, `lastFailedAt`, and bounded `lastReason`. If business processing fails, the inbox and business transaction rolls back. After rollback, the Kafka error handler opens a separate short transaction and creates or increments the consumer-failure record.

On failure five, the error-handler transaction atomically records inbox status `DEAD` and a consumer dead-letter record unique by `(consumer_name, message_id)`. The consumer acknowledges the Kafka record only after that transaction commits. An unsupported schema version with an otherwise valid envelope and `messageId` uses the same terminal inbox/dead-letter transaction immediately, without four preceding retries. A malformed payload inside a valid envelope uses normal bounded consumer-failure counting unless classified as permanently invalid, in which case it may enter the normal consumer dead letter immediately. Successful business processing records inbox status `PROCESSED`. A consumer dead-letter record preserves the envelope, payload hash, topic, partition, offset, consumer name, reason code, bounded safe error summary, correlation data, first failure time, and dead-letter time.

If the Kafka envelope cannot be parsed sufficiently to obtain a valid `messageId`, Messaging creates no inbox or normal consumer-failure record. It immediately creates one transport dead-letter record unique by:

```text
UNIQUE (consumer_name, topic, partition, offset)
```

The transport dead letter preserves the raw-record hash, bounded safe bytes or summary, topic, partition, offset, consumer name, reason, and correlation data when safely available. The Kafka record is acknowledged only after the transport dead-letter transaction commits.

Release 0.2 exposes dead records through metrics, structured logs, documented read-only database queries, and runbooks. It provides no public manual replay endpoint. Release 0.2 runs no automatic purge for outbox, inbox, dead-letter, Provider evidence, Payment Attempt, accepted-result, or webhook receipt records; they remain until a deliberate demonstration-environment reset.

### Durable provider command handling

The provider-command consumer performs this sequence:

1. In one transaction, insert the consumer inbox record and create or locate the Provider work item.
2. Commit before any network call.
3. Claim due Provider work through a 30-second recoverable lease.
4. Call the Provider Simulator outside every database transaction.
5. In a later short Provider transaction, append immutable interaction and result evidence, update work scheduling state, append `ProviderResultObserved` through `messaging::api` when a result was observed, and commit.

Provider work uses these states:

```text
PENDING | RETRYABLE | CLAIMED | WAITING_RETRY_REQUEST | WAITING_STATUS | COMPLETED | UNRESOLVED
```

Provider work distinguishes at least these work types:

```text
SUBMISSION
STATUS_QUERY
```

Every Provider work record contains `leaseOwner`, `leaseToken`, and `leaseExpiresAt`. Provider-call claim candidates are due `PENDING`, due `RETRYABLE`, and `CLAIMED` records whose lease has expired. Only `STATUS_QUERY` work may move from `WAITING_STATUS` to `RETRYABLE` and perform another Provider HTTP call. Due `WAITING_RETRY_REQUEST` work is claimed directly for the retry-request transaction, receives a new `leaseToken`, and never enters the Provider-call path. Each claim assigns a new `leaseToken`.

Lease renewal and every mutable Provider work update require the current `leaseToken`. A stale worker cannot mark reclaimed work `COMPLETED`, `WAITING_STATUS`, `WAITING_RETRY_REQUEST`, or `UNRESOLVED`. A stale worker might already have called the Provider, so stable Provider idempotency, immutable evidence identities, and duplicate result handling remain mandatory. Only the current fenced lease holder can update mutable work state.

A `SUBMISSION` work item in `WAITING_RETRY_REQUEST` never becomes `RETRYABLE` and never calls the Provider again for the same Payment Attempt. When its due time arrives, the current fenced worker performs one short transaction:

1. verify the current `leaseToken`;
2. create or find one immutable Provider retry-request record;
3. append `PaymentSubmissionRetryRequested` through `messaging::api`;
4. mark the original Provider work `COMPLETED`; and
5. commit once.

The Provider retry-request record contains `retryRequestId`, `tenantId`, `paymentId`, `previousAttemptId`, `providerEvidenceId`, `providerId`, and `requestedAt`. PostgreSQL uniqueness is exactly:

```text
UNIQUE (tenant_id, provider_evidence_id)
```

The `retryRequestId` is generated once and persisted in that record. Repeated scheduling or crash recovery returns the same `retryRequestId` and outbox record. Provider appends with `producerName = provider` and `deduplicationKey = payment-retry:<retryRequestId>`.

Payment later consumes `PaymentSubmissionRetryRequested` and creates the next immutable Payment Attempt. Its `SubmitPaymentToProvider` command creates new `SUBMISSION` work for that new attempt. Intentional Payment resubmission is never a repeat execution of the original `SUBMISSION` work.

Submission work is unique by `(tenant_id, attempt_id, work_type)`. Duplicate command delivery therefore creates one work item even if a malformed producer supplies another message ID.

A crash after command consumption cannot lose work because the inbox and work item committed together. A crash after provider acceptance but before local result persistence is recovered through the stable provider idempotency key and status query. An expired work lease permits recovery; the stable provider key prevents a second provider-side transaction.

### Two-stage provider-result flow

#### Stage A: Provider evidence transaction

Provider:

1. appends immutable interaction and result evidence;
2. appends one `ProviderResultObserved` outbox record in the same transaction; and
3. commits once.

Every mapped Provider work item, interaction, result, and webhook receipt is tenant-owned. The webhook boundary may preserve the explicitly defined Provider-scoped unattributed evidence before tenant mapping; unattributed evidence cannot create work or a Payment effect. Provider result evidence is unique by `(tenant_id, provider_id, provider_result_id)`. The Provider Simulator returns the same `providerResultId` for the same provider-side state transition through the submission response, status query, and webhook. Duplicate observations may remain visible as interaction or receipt evidence, but an identical provider result does not append another logical result message. Reuse of one tenant/provider/result identity with different result content is a durable Provider conflict.

#### Stage B: Payment result transaction

Payment:

1. inserts the `payment-provider-result-consumer-v1` inbox record;
2. verifies through `provider::api` that the referenced immutable Provider evidence exists and matches the tenant, attempt, Payment, provider, category, and provider reference;
3. validates the immutable Payment Attempt and current Payment state;
4. applies the result matrix;
5. records the accepted final Provider evidence when a terminal transition occurs;
6. appends `PaymentCompleted` or `PaymentFailed` through `messaging::api`; and
7. commits once.

For `SUCCESS`, Step 4 invokes the existing `CompletePaymentAfterProviderSuccess`. The completion, Ledger posting, inbox record, accepted-result record, and `PaymentCompleted` outbox record commit or roll back together.

An event without matching durable Provider evidence is invalid and cannot complete or fail a Payment. It is retried only for a bounded evidence-visibility failure; a permanent mismatch becomes a dead letter and operational failure.

### Provider result matrix

`ProviderResultCategory` remains exactly:

```text
SUCCESS
ACCEPTED
DECLINED
PENDING
TEMPORARY_FAILURE
PERMANENT_FAILURE
UNKNOWN
```

Provider owns `RetryDisposition`, which contains exactly:

```text
SAFE_TO_RESUBMIT
STATUS_RECOVERY_REQUIRED
NOT_RETRYABLE
```

| Category | Retry disposition | Payment behavior | Provider behavior |
|---|---|---|---|
| `SUCCESS` | `NOT_RETRYABLE` | For eligible `PROCESSING`, invoke the exact ADR-020 completion. | Preserve final evidence; schedule no submission retry. |
| `DECLINED` | `NOT_RETRYABLE` | For eligible `PROCESSING`, apply `Payment.fail()`. | Preserve final evidence; schedule no retry. |
| `PERMANENT_FAILURE` | `NOT_RETRYABLE` | A contract-valid definitive result may apply `Payment.fail()` while `PROCESSING`. | Preserve final evidence; schedule no retry. A non-definitive outcome must be categorized `UNKNOWN`, not `PERMANENT_FAILURE`. |
| `ACCEPTED` | `STATUS_RECOVERY_REQUIRED` | Leave Payment `PROCESSING`. | Schedule status recovery. |
| `PENDING` | `STATUS_RECOVERY_REQUIRED` | Leave Payment `PROCESSING`. | Schedule status recovery. |
| `TEMPORARY_FAILURE` | `SAFE_TO_RESUBMIT` only when durable evidence proves that no Provider transaction was accepted or created; otherwise `STATUS_RECOVERY_REQUIRED`. | Leave Payment `PROCESSING`. | Request a new attempt only for `SAFE_TO_RESUBMIT`; otherwise schedule status recovery. |
| `UNKNOWN` | `STATUS_RECOVERY_REQUIRED` | Leave Payment `PROCESSING`. | Schedule status recovery and never blindly resubmit. |

Only durable evidence with `RetryDisposition.SAFE_TO_RESUBMIT` may produce `PaymentSubmissionRetryRequested`. `ProviderResultObserved` carries the disposition, but Payment treats the event as a claim, not authority. Before creating an attempt, Payment verifies the authoritative Provider evidence and disposition through `provider::api`.

A status query that returns an existing Provider transaction prohibits resubmission and requires `NOT_RETRYABLE` for a final category or `STATUS_RECOVERY_REQUIRED` for a non-final category.

A terminal Payment never regresses. An exact duplicate of the accepted final result returns the existing result without another lifecycle or financial effect. A different final category, provider reference, attempt, or evidence identity after a final result is a durable conflict. The system preserves it as Provider evidence and dead-letter or operational evidence; it does not overwrite the accepted result.

### Retry, timeout, and recovery limits

All values below are Release 0.2 decisions, not hidden configuration defaults.

| Policy | Release 0.2 value |
|---|---|
| Maximum automatic Payment Attempts | 3 total: one initial attempt and at most two intentional safe retries. |
| Low-level pre-transmission retry | One retry within the same Payment Attempt only when the HTTP client proves that no request bytes were sent, such as DNS resolution, connection refusal, or TLS setup failure before application data. Delay 200 ms with deterministic ±20% jitter. |
| Ambiguous transport outcome | Any write, read, response, or total timeout after transmission might have begun becomes `UNKNOWN`; no submission retry occurs. |
| Intentional submission retry delay | 5 seconds, then 10 seconds, capped at 60 seconds, with deterministic ±20% jitter. Each retry creates a Payment Attempt. |
| Maximum status-query attempts | 12 per unresolved provider transaction. |
| Status-query delay | Starts at 10 seconds, doubles to a 5-minute cap, with deterministic ±20% jitter. |
| Connection timeout | 1 second. |
| Read timeout | 3 seconds. |
| Total provider-call timeout | 5 seconds. |
| Publisher and Provider work claim lease | 30 seconds, renewable every 10 seconds while active. |
| Outbox publication attempts | 10; attempt 10 failure becomes `DEAD`. |
| Consumer poison-message deliveries | 5 for a valid envelope; an unsupported schema version with a valid `messageId` dead-letters immediately through the normal inbox path; an envelope without a trustworthy `messageId` uses the immediate transport dead-letter path. |
| Webhook body-size limit | 256 KiB. |
| Webhook timestamp tolerance and replay window | Absolute clock difference no greater than 300 seconds. |

When safe submission retries are exhausted, a temporary failure does not become a false Payment failure. When status recovery is exhausted, Payment remains `PROCESSING`. Provider marks the work `UNRESOLVED`, persists the reason and last safe evidence, emits an operational signal, and creates no new Payment status.

Circuit-breaker and bulkhead values are also explicit:

- count-based circuit-breaker window: 20 calls;
- minimum calls before evaluation: 10;
- failure-rate threshold: 50 percent;
- slow-call threshold: 3 seconds;
- slow-call-rate threshold: 50 percent;
- open duration: 30 seconds;
- permitted half-open calls: 3;
- maximum concurrent provider calls: 10 per Core instance; and
- bulkhead wait time: zero.

An open circuit reschedules durable work without consuming a Payment Attempt or status-query attempt because no provider call occurred.

### Signed Provider HTTP contract

Core-to-Simulator submissions and status queries, and Simulator-to-Core webhooks, use HMAC-SHA256. `Core -> Provider Simulator` and `Provider Simulator -> Core` use separate secret sets. Secrets are injected through environment-specific secret configuration and never appear in source, logs, seed data, dashboards, or documentation.

Each key ID maps to exactly one provider, one provider-client identity, and one direction. An unknown key or a key presented in the wrong direction returns `401 Unauthorized`. Because that request has no trusted tenant mapping, it creates no Provider receipt; it creates only bounded platform security rejection evidence and metrics. The verified key mapping establishes provider and provider-client identity; request payloads and webhook bodies cannot override that identity.

Required outbound request headers:

```text
Content-Type: application/json
X-LedgerOps-Key-Id
X-LedgerOps-Timestamp
X-LedgerOps-Request-Id
X-LedgerOps-Signature
```

Required webhook headers:

```text
Content-Type: application/json
X-LedgerOps-Key-Id
X-LedgerOps-Timestamp
X-LedgerOps-Event-Id
X-LedgerOps-Signature
```

`X-LedgerOps-Timestamp` is Unix epoch seconds. Request and event IDs are canonical lowercase UUID strings. `traceparent` and `tracestate` propagate separately and are not signed.

The exact UTF-8 canonical byte sequence is:

```text
v1
<UPPERCASE_HTTP_METHOD>
<RAW_PATH_WITHOUT_QUERY>
<KEY_ID>
<TIMESTAMP_DECIMAL>
<REQUEST_ID_OR_EVENT_ID>
<LOWERCASE_SHA256_RAW_BODY>
```

Each displayed line is separated by one ASCII line-feed byte (`0x0A`), and the canonical sequence has no trailing line feed.

The signature header is:

```text
X-LedgerOps-Signature: v1=<base64url-without-padding HMAC-SHA256>
```

Verification decodes the supplied MAC and compares bytes in constant time. Proxies must preserve the signed raw path. Release 0.2 provider endpoints do not use query strings.

The Provider Simulator exposes `POST /provider/v1/payments` for signed submission and `POST /provider/v1/payment-status-queries` for signed status query. Core exposes `POST /internal/provider/v1/webhooks` for signed Simulator webhooks. The Provider Simulator creates its Provider idempotency record when it accepts a request into its durable Provider transaction. Provider-side processing is idempotent by `(providerClientId, providerIdempotencyKey)`. Once that record exists, repeating the same key with equivalent content returns the original Provider transaction and cannot create another Provider-side transaction. Different content returns a conflict and never replaces the original transaction.

### Webhook reception and processing

Webhook reception performs this sequence before returning its final response:

1. enforce the 256 KiB body limit;
2. capture the raw bytes and calculate the SHA-256 hash;
3. parse required authentication headers without trusting payload fields;
4. resolve the provider, provider-client identity, and direction from the key ID;
5. validate the 300-second timestamp window and verify HMAC in constant time;
6. only after successful authentication, parse and validate the payload;
7. resolve tenant, Payment, and Payment Attempt identity from the Provider-owned command/work mapping associated with the provider idempotency identity;
8. when mapping succeeds, insert a tenant-owned immutable receipt, deduplicate by `(tenant_id, provider_id, provider_event_id)`, compare payload hashes, and create or locate one Provider webhook work item for a new verified event.

HTTP behavior is exact:

- unknown key, wrong-direction key, invalid signature, or invalid timestamp: persist bounded platform security rejection evidence only, create no Provider receipt or work, and return `401 Unauthorized`;
- valid signature but malformed JSON: persist Provider-scoped unattributed invalid evidence, create no tenant-owned receipt or work, and return `400 Bad Request`;
- valid signature and valid payload but no Provider-owned mapping: persist a durable unattributed Provider receipt, create an operational signal, create no tenant-owned work, and return `202 Accepted`;
- new valid event: persist receipt and work, then return `202 Accepted`;
- same provider event ID and same payload hash: persist a duplicate receipt, create no new work, then return `202 Accepted`;
- same provider event ID and different payload hash: persist a `CONFLICT` receipt and durable operational failure, create no work, then return `409 Conflict`;
- oversized body: return `413 Payload Too Large` and persist only bounded rejection metadata when available.

Only the first authenticated, mapped event creates the canonical tenant-owned Provider event. An invalid or unattributed delivery cannot reserve or poison a tenant-owned provider event ID. Duplicate, invalid, out-of-order, conflicting, and unattributed evidence remains immutable for the life of the demonstration environment. Webhook body fields never establish tenant identity by themselves.

Webhook processing is asynchronous and Provider-owned. The worker loads the canonical receipt, resolves tenant, Payment, and Payment Attempt identity from Provider-owned mappings created from `SubmitPaymentToProvider`, appends Provider interaction/result evidence, and follows Stage A. Provider does not query Payment or depend on `payment::api`. A duplicate or out-of-order observation never regresses a terminal Payment and never creates another Ledger effect.

### Provider Simulator deployment and scope

The Provider Simulator is a separate Spring Boot Gradle subproject at:

```text
applications/provider-simulator
```

The existing Core Platform remains at the repository root. The simulator has its own PostgreSQL database, migrations, application configuration, and Testcontainers tests. It never receives Core database credentials and never reads or writes Core schemas.

The simulator supports deterministic Release 0.2 scenarios for success, decline, timeout, temporary HTTP failure, slow response, delayed webhook, duplicate webhook, missing webhook, out-of-order webhook, invalid signature, conflicting final result, and timeout-then-success recovery. Scenario configuration is seeded, test-owned, or local-profile configuration. Release 0.2 does not expose an unauthenticated administrative scenario API. Settlement-file generation remains Release 0.3.

### Observability

OpenTelemetry context propagates across inbound HTTP, outbox records, Kafka headers, consumer spans, Provider HTTP requests, Provider Simulator spans, and webhooks. Each asynchronous consumer creates a processing span from the extracted context and preserves `correlationId` and `causationId`. High-cardinality Payment, attempt, message, provider reference, and trace identifiers appear only in logs and traces.

Release 0.2 adds these bounded Prometheus measures:

- `ledgerops_outbox_pending`;
- `ledgerops_outbox_oldest_age_seconds`;
- `ledgerops_outbox_publish_total{outcome}`;
- `ledgerops_inbox_duplicate_total{consumer}`;
- `ledgerops_consumer_dead_total{consumer,reason}`;
- `ledgerops_kafka_consumer_lag{consumer,topic}`;
- `ledgerops_provider_http_duration_seconds{operation,outcome}`;
- `ledgerops_provider_result_total{category}`;
- `ledgerops_provider_timeout_total{operation}`;
- `ledgerops_provider_ambiguity_total`;
- `ledgerops_provider_retry_backlog`;
- `ledgerops_provider_recovery_backlog`;
- `ledgerops_webhook_receipt_total{verification}`;
- circuit-breaker state with bounded circuit and state labels; and
- `ledgerops_duplicate_financial_effect_total`, whose target is zero.

Tenant IDs, Payment IDs, attempt IDs, message IDs, provider references, correlation IDs, and trace IDs are prohibited Prometheus labels.

Initial Grafana dashboards cover messaging delivery and Provider operations. Alerts link to the Kafka outage, outbox backlog, consumer lag, financial dead-letter, Provider outage, ambiguous outcome, webhook backlog, and stuck Payment runbooks.

### Release boundary

Release 0.2 includes Kafka, outbox, inbox, JSON Schema contracts, Payment Attempts for Payments, Provider evidence and recovery, a separate Provider Simulator, HMAC requests and webhooks, bounded resilience, dead-letter handling, OpenTelemetry, Prometheus, initial Grafana dashboards, and release-appropriate automated evidence.

Release 0.2 excludes Keycloak and authorization, Reversal implementation, settlement files and reconciliation, casework and corrections, a polished frontend, Kubernetes, Terraform, AWS deployment, applied AI, Redis without a separately approved need, and public manual replay or sensitive administrative controls.

## Consequences

Positive:

- A committed provider command survives Kafka and process outages.
- Every external call is preceded by durable work and occurs outside a PostgreSQL transaction.
- Duplicate publication and delivery are expected and safe.
- Provider evidence exists before Payment can accept a result.
- `SUCCESS` reuses the exact ADR-020 completion and Ledger replay contract.
- Timeout and ambiguity remain recoverable without false failure or blind resubmission.
- Retry, lease, webhook, and dead-letter limits are reviewable requirements rather than hidden defaults.
- The simulator is operationally separate and cannot couple to Core persistence.

Negative or costly:

- The design adds three persistence ownership areas: Payment attempts/final application, generic messaging, and Provider evidence/work.
- At-least-once delivery requires idempotency at both message and business levels.
- One logical provider interaction can have several immutable communication observations.
- Crashes and lease expiry can repeat network calls, so provider idempotency and status query are mandatory.
- A Payment may remain `PROCESSING` indefinitely after automated recovery is exhausted until Release 0.3 adds authorized investigation controls.
- Kafka, two PostgreSQL databases, tracing, metrics, dashboards, and failure testing increase local test and operational cost.

## Alternatives considered

### One outbox per business module

Rejected. Separate implementations would duplicate claim, lease, retry, dead-letter, schema, and telemetry behavior and could create incompatible guarantees. A narrow generic module preserves business ownership while centralizing delivery mechanics.

### Publish directly to Kafka from a business transaction

Rejected. Kafka and PostgreSQL cannot commit atomically in this design, so a process failure could lose a committed command or publish an uncommitted fact.

### Claim end-to-end exactly-once delivery

Rejected. Kafka acknowledgement and outbox marking can be separated by a crash. At-least-once delivery with inbox and business idempotency describes the actual guarantee.

### Call the Provider Simulator inside the Kafka consumer transaction

Rejected. A slow or unavailable provider would hold database locks and make crash recovery ambiguous. Durable work separates message consumption from network execution.

### Recover expired leases without fencing tokens

Rejected. A stale publisher or Provider worker could overwrite a newer holder's state after an expired claim is reclaimed. A new token per claim and compare-and-set updates fence mutable state while duplicate handling covers external effects that occurred before fencing.

### Complete Payment directly from a synchronous provider response

Rejected. Payment could complete without durable Provider evidence. Stage A must commit evidence and its result outbox before Stage B can apply the Payment result.

### Replace `CompletePaymentAfterProviderSuccess`

Rejected. The existing use case already proves the required lock, exact posting, replay, rollback, source uniqueness, and module boundary. A second path could create divergent financial semantics.

### Use only inbox deduplication for final outcomes

Rejected. Inbox deduplicates one `messageId`, but the same provider fact can arrive through synchronous response, status query, and webhook with different message IDs. Payment must also validate its accepted final-result evidence and terminal state.

### Blindly retry after timeout

Rejected. A timeout can occur after provider acceptance. Blind resubmission can duplicate provider processing. The system records `UNKNOWN` and queries status with the same provider idempotency identity.

### Create a Payment Attempt for every duplicate command delivery

Rejected. Payment Attempts represent intentional submissions, not transport duplication. Command inbox and Provider-work uniqueness absorb duplicate delivery.

### Let Provider call Payment directly for safe retry

Rejected. A `Provider -> payment::api` dependency would combine with Payment's required `Payment -> provider::api` evidence verification and create a module cycle. `PaymentSubmissionRetryRequested` preserves durable coordination through messaging while keeping the dependency graph acyclic.

### Store provider progress in `PaymentStatus`

Rejected by ADR-016. Provider work, retry, webhook, ambiguity, and recovery remain separate dimensions.

### Use Redis for work scheduling or deduplication

Rejected for Release 0.2. PostgreSQL provides durable authoritative work, leases, uniqueness, and transactional composition. No approved Release 0.2 requirement demonstrates a need for Redis.

### Move Core into `applications/core-platform`

Rejected for this release. The current root project is complete and verified. Adding the Provider Simulator as a subproject does not justify a broad source-tree move.

### Add Provider routing or failover in Release 0.2

Rejected. Release 0.2 proves the distributed boundary against exactly `SIMULATOR`. Routing, failover, Merchant selection, and multi-Provider policy add product and operational decisions that require later approval.

### Expose public replay and scenario-administration endpoints

Rejected for Release 0.2. Those controls require the Release 0.3 authorization and audit model. Tests, seed data, local profiles, metrics, and runbooks provide release evidence without unsafe public controls.

## Impact assessment

- Product requirements: Release 0.2 exits with `PAY-03`, `PAY-04`, `PRV-01` through `PRV-05`, `DEV-01`, `DEV-03`, `DEV-04`, `BR-01`, `BR-12`, and `BR-13` at `Partial`. `DEV-02` remains `Deferred` or `Planned`; Provider Simulator-to-Core webhooks do not implement merchant webhook testing. `BR-05` and `BR-06` become `Implemented` only after all required executable evidence passes.
- Data and migration: Adds new Payment-owned tables, a `messaging` schema, and a `provider` schema through forward Flyway migrations. Released V1–V7 migrations remain immutable. No cross-schema foreign keys are permitted.
- Security and tenancy: Every mapped Provider work, interaction, result, and receipt record has non-null `tenant_id`. The webhook boundary stores only the specified bounded platform rejection evidence or Provider-scoped unattributed evidence before mapping; neither can create work or a Payment effect. HMAC covers version, method, path, key ID, timestamp, request/event ID, and raw-body hash. Trace context propagates separately. Direction-specific key mappings establish provider and provider-client identity. Secrets and full signatures are excluded from logs and metrics. Public replay and scenario administration remain excluded.
- Testing: Requires PostgreSQL, Kafka, Provider Simulator, crash-recovery, contract, concurrency, webhook, resilience, trace-propagation, metrics, and end-to-end evidence. The existing ADR-020 rollback and replay suite remains mandatory.
- Operations and reliability: Adds recoverable leases, bounded retries, durable unresolved outcomes, dead-letter evidence, metrics, dashboards, and runbooks. End-to-end exactly-once delivery remains explicitly unsupported.
- Cost and delivery: Adds Kafka, a second application/database, and telemetry components. Vertical slices produce observable workflows incrementally and exclude Release 0.3 infrastructure.
- Documentation: Requires a Technical Specification revision after acceptance, Release 0.2 traceability, event and provider contracts, diagrams, runbooks, a demo, README and AGENTS synchronization, and retrospective ADR record creation.

## Retrospective ADR records

Slice 0 added these four repository records without changing their established decisions:

| Retrospective file | Recorded decision | Required provenance statement |
|---|---|---|
| `docs/adr/ADR-004-use-transactional-outbox.md` | Use a transactional outbox so business state and outbound-message intent commit atomically. | “This file is a retrospective record of a decision originally approved in LedgerOps Technical Specification v1.0 on 13 July 2026. It is not a recovered original and introduces no new decision.” |
| `docs/adr/ADR-005-use-at-least-once-messaging-with-idempotent-consumers.md` | Use at-least-once delivery with idempotent consumers; do not claim end-to-end exactly-once delivery. | Same required provenance statement, naming ADR-005. |
| `docs/adr/ADR-006-use-business-capability-kafka-topics.md` | Use business-capability Kafka topics and aggregate partition keys. | Same required provenance statement, naming ADR-006. |
| `docs/adr/ADR-009-run-provider-simulator-as-a-separate-application.md` | Run the Provider Simulator as a separate deployable application with its own database. | Same required provenance statement, naming ADR-009. |

Each retrospective record must use the repository ADR structure, carry the original approval date of 13 July 2026, cite Technical Specification v1.0 as its authority, and label itself `Retrospective record`. Creating these files restores repository traceability only. It does not reopen, replace, or extend the accepted decisions.

## Affected Product Definition sections

No Product Definition change is proposed. ADR-021 refines the implementation of existing Product Definition v1.6 requirements without changing product behavior, lifecycle states, ownership, permissions, or release criteria.

Relevant sections are §§5.1, 6.4, 7.3, 7.5, 7.12, 8.1–8.2, 10–12; PAY-03, PAY-04; PRV-01 through PRV-05; DEV-01 through DEV-04; BR-01, BR-05, BR-06, BR-12, and BR-13.

## Affected Technical Specification sections

- Document control, revision history, and implementation authorization
- §3.1 Logical systems and §3.3 Boundary rules
- §4.1 Package structure, §4.3 Module ownership, and §4.4 Cross-module interaction rules
- §5.1 Primary aggregates, §5.3 Payment state machine, §5.6 Atomic Payment completion, and §5.7 Idempotency and concurrency
- §6.1 Database topology, §6.2 Data ownership, and §6.4 Schema migration
- §§7.1–7.8 Event architecture and delivery guarantees
- §8.1 Authentication and §8.6 Data and API security
- §§9.1–9.6 Provider integration and Payment orchestration
- §§11.1–11.6 Observability, reliability, and performance
- §§12.1–12.3 Environment and repository structure
- §§13.1–13.4 Testing and verification
- §14.2 Release 0.2
- §15 Risks and mitigations
- §16 Architecture decision register
- §§17.2–17.4 Release acceptance criteria
- Appendix C operational runbooks

## Review conditions

Reconsider this decision through a new or superseding ADR if:

- measured throughput or contention shows that PostgreSQL polling and 30-second leases cannot meet the Release 0.2 SLOs;
- a real provider cannot support stable idempotency and status query;
- retry limits cause unacceptable unresolved volume or duplicate risk;
- the Provider Simulator contract requires query strings or non-JSON signing;
- one generic messaging schema prevents module isolation or independent delivery ownership;
- Kafka topic hot spots appear despite Payment-ID partitioning;
- a future service extraction removes the shared Core PostgreSQL transaction used by Stage B; or
- Release 0.3 authorization permits controlled manual replay or scenario administration.

## Approval

- Product owner: Approved
- Architecture owner: Approved
- ADR-004/005/006/009 retrospective repository records: Created
- Technical Specification reconciliation: Completed
- ADR status: Accepted
- Release 0.2 implementation authorization: Granted after completion of Slice 0
