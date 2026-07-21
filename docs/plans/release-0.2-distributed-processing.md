# Release 0.2 plan: Distributed Processing

Status: Completed
Owner: One implementation owner per vertical slice
Release: 0.2 — Distributed Processing
Last updated: 2026-07-22

## Outcome

Release 0.2 is complete. Committed provider commands survive Kafka and process outages; duplicate commands, results, and webhooks produce one business and financial effect; Provider calls run outside database transactions; definitive `SUCCESS` reaches the existing ADR-020 atomic Payment-to-Ledger boundary; other Provider result categories follow their approved behavior; retry and status recovery stop at explicit limits; unresolved outcomes and dead messages remain durable and visible; and traces, metrics, contracts, and automated failure tests prove those guarantees.

Release 0.2 extends the completed Release 0.1 Core Platform. It does not replace `CompletePaymentAfterProviderSuccess`, change `PaymentStatus`, move the existing Core source tree, or claim end-to-end exactly-once delivery.

## Audience and reader goal

This plan is for the engineer implementing and reviewing Release 0.2. It defines the approved-order work needed to add distributed Provider processing without weakening the transactional and financial evidence completed in Release 0.1.

Slices 0 through 7 are complete. ADR-021 is accepted, its decisions are incorporated into Technical Specification v1.6, and every Release 0.2 implementation slice has executable evidence. Release 0.3 has not started.

## Authority and requirement mapping

- Product baseline: [LedgerOps Product Definition v1.6](../product/LedgerOps_Product_Definition_Official_v1.6.docx)
- Technical baseline: [LedgerOps Technical Design and Architecture Specification v1.6](../architecture/LedgerOps_Technical_Design_and_Architecture_Specification_v1.6.docx)
- Accepted Release 0.2 decision: [ADR-021](../adr/ADR-021-define-release-0.2-provider-and-messaging-semantics.md)
- Retrospective accepted decisions: [ADR-004](../adr/ADR-004-use-transactional-outbox.md), [ADR-005](../adr/ADR-005-use-at-least-once-messaging-with-idempotent-consumers.md), [ADR-006](../adr/ADR-006-use-business-capability-kafka-topics.md), and [ADR-009](../adr/ADR-009-run-provider-simulator-as-a-separate-application.md)
- Preserved completion decision: [ADR-020](../adr/ADR-020-define-release-0.1-payment-success-posting.md)
- Lifecycle and ownership decision: [ADR-016](../adr/ADR-016-align-payment-reversal-provider-and-sensitive-action-semantics.md)
- Completed baseline: [Release 0.1 plan](release-0.1-transactional-core.md)

ADR-004, ADR-005, ADR-006, and ADR-009 are retrospective repository records of decisions originally approved in Technical Specification v1.0 on 13 July 2026. They are not recovered originals and introduce no new decisions.

### Product requirement mapping

| Product authority | Release 0.2 interpretation | Approved status at release exit |
|---|---|---|
| PAY-03; §8.2 | Preserve the exact Payment lifecycle. Initial submission moves `APPROVED -> PROCESSING`; final `SUCCESS` uses ADR-020; definitive decline or permanent failure may move `PROCESSING -> FAILED`. | Partial; Release 0.2 implements the automated Provider path, not every Payment workflow. |
| PAY-04 | Persist Payment Attempt and Provider evidence needed by a future composed Payment detail view. | Partial; no operations UI in Release 0.2. |
| PRV-01 | Provide deterministic Provider Simulator scenarios through tests, seed/configuration, and local profiles. | Partial; authenticated platform-administration workflow remains Release 0.3. |
| PRV-02 | Persist immutable Payment Attempts for Payment submissions/retries and separate Provider-owned evidence. | Partial; Payment attempts implemented, Reversal attempts deferred to Release 0.3. |
| PRV-03 | Persist duplicate, invalid, out-of-order, and conflicting webhook receipts and processing outcomes. | Partial; evidence and behavior are implemented, but the user-facing timeline remains later. |
| PRV-04 | Apply explicit automatic safe-retry and ambiguity-recovery policies; expose state through evidence, metrics, and runbooks. | Partial; public manual retry requires Release 0.3 authorization/audit. |
| PRV-05 | Measure Provider success, timeout, error, category, and latency behavior and expose initial dashboards. | Partial; initial Grafana dashboards do not complete the product-facing operations experience. |
| DEV-01 | Extend developer reference with signed Provider/webhook and event contracts. | Partial until Release 0.3 authentication instructions and portfolio experience are complete. |
| DEV-02 | Support merchant webhook testing. | Deferred or Planned; Provider Simulator-to-Core webhooks test the Provider boundary and do not implement merchant webhook testing. |
| DEV-03 | Add deterministic Release 0.2 failure stories and reset instructions. | Partial; no authenticated scenario launcher UI. |
| DEV-04 | Add synthetic Provider, attempt, messaging, and failure evidence to the Release 0.2 demo. | Partial across the portfolio product; Release 0.2 data remains synthetic. |
| BR-01 | Every new Core business record has mandatory tenant ownership unless it is explicitly platform-wide infrastructure metadata. | Partial; Release 0.2 proves the rule for its implemented records only. |
| BR-05 | Duplicate Provider results create one effective Payment and financial result. | Implemented only after every required duplicate, concurrency, replay, and financial-effect test passes. |
| BR-06 | Automatic retries stop when safety is uncertain or configured limits are reached. | Implemented only after every required retry-disposition, attempt-limit, ambiguity, and exhaustion test passes. |
| BR-12 | Scheduling, leases, signatures, and evidence use injected time consistently. | Partial; Release 0.2 covers its distributed-processing paths. |
| BR-13 | Provider progress remains separate from Payment, Reversal, and Reconciliation status. | Partial; Release 0.2 enforces the boundary for Provider processing. |
| §§10–12 | Recover safely, preserve correlation and evidence, and report Provider/messaging behavior without secrets. | Partial at the Release 0.2 boundary. |

### Technical section mapping

| Technical authority | Release 0.2 evidence target |
|---|---|
| §§1.1–1.2 | At-least-once Kafka, PostgreSQL truth, duplicate safety, timeout ambiguity, and no network calls inside long transactions. |
| §§3.1–3.3 | Separate Provider Simulator and database; no Core database access. |
| §§4.1–4.4 | New `messaging` and `provider` Modulith boundaries with published APIs and no cross-table access. |
| §§5.1, 5.3, 5.6–5.7 | Immutable attempts, exact lifecycle, preserved completion transaction, locks, source uniqueness, and final-result replay. |
| §§6.1–6.5 | Module-owned PostgreSQL schemas, tenant ownership, forward Flyway migrations, and Kafka as transport only. |
| §§7.1–7.8 | Versioned JSON/JSON Schema contracts, business topics, Payment-ID partitioning, outbox, inbox, at-least-once delivery, dead letters, and compatibility. |
| §§8.1 and 8.6 | HMAC Provider boundary, replay window, size limits, secret-safe evidence, and no Release 0.3 identity claim. |
| §§9.1–9.6 | Provider adapter, durable submission/result flow, exact categories, resilience, webhook stages, and deterministic Simulator scenarios. |
| §§11.1–11.6 | Trace propagation, bounded metrics, dashboards, SLO evidence, and crash/backlog recovery. |
| §§12.1–12.3 | Root Core project retained; separate `applications/provider-simulator` subproject and local/test topology. |
| §§13.1–13.4 | Domain, PostgreSQL, Kafka, Provider, contract, concurrency, crash, failure, and end-to-end verification. |
| §14.2 | Exact Release 0.2 scope and exit outcome. |
| §§15–17 and Appendix C | Event-loss mitigation, acceptance evidence, operational visibility, and runbooks. |

## Slice 0 baseline snapshot

Repository inspection before implementation on 2026-07-21 established this historical Slice 0 baseline. The completed-slice sections and verification matrix below supersede it as implementation evidence is added:

- `settings.gradle.kts` declares one root project, `core-platform`, with no subprojects.
- `build.gradle.kts` uses Java 21, Spring Boot 4.1.0, Spring Modulith 2.1.0, Spring Data JPA/JDBC, Flyway, PostgreSQL, Actuator, Web MVC, validation, JUnit, and PostgreSQL Testcontainers.
- No Kafka, Spring Kafka, Provider, Payment Attempt, outbox, inbox, dead-letter, Resilience4j, OpenTelemetry, Prometheus registry, Grafana, WireMock, or Kafka Testcontainers production/test dependency exists.
- Spring Modulith declares `tenancy`, `merchant`, `customer`, `payment`, `risk`, and `ledger`. Payment depends only on published APIs from Customer, Ledger, Merchant, Risk, and Tenancy.
- `PaymentStatus` and `Payment` implement exactly the ADR-016 states and transitions, including `startProcessing()`, `complete()`, and `fail()`.
- Payment persistence has tenant-scoped reads, optimistic compare-and-set, a `version` column, and tenant-scoped `FOR UPDATE` locking for completion.
- `CompletePaymentAfterProviderSuccess` is an internal `@Transactional` Payment application service. It validates the complete state/posting matrix and calls `PaymentSuccessLedger` only.
- `ledger::api` exposes neutral request and posting-evidence records. Ledger joins the caller's transaction with `Propagation.MANDATORY`.
- Ledger enforces source uniqueness on `(tenant_id, source_type, source_id)` and validates tenant, source, amount, currency, exact two-entry shape, account directions, and compensation state on replay.
- Flyway V1–V7 own the Tenancy, Merchant, Customer, Payment, Risk, and Ledger schemas. Released migrations are immutable.
- Testcontainers starts PostgreSQL 17 only. Release 0.1 has 224 passing tests covering domain rules, migrations, concurrency, rollback, Modulith boundaries, HTTP/OpenAPI errors, logging, and demo seed behavior.
- OpenAPI v0.1 documents tenant and Payment creation only. Logs use `X-Correlation-Id`/MDC correlation. The Release 0.1 demo starts one PostgreSQL container and contains no distributed infrastructure.
- At the Slice 0 baseline, Release 0.1 was complete and Release 0.2 had no production implementation. Slice 0 changed documentation only; Slices 1–7 subsequently delivered the executable evidence recorded below.

## Reconciliation and contradiction gate

The approved Release 0.2 direction does not conflict with Product Definition v1.6, Technical Specification v1.6, ADR-016, ADR-020, or ADR-021.

ADR-021 resolves these Technical Specification ambiguities without changing product behavior:

1. Technical §9.2 says `PaymentCompleted` or `PaymentFailed` is published after the state change commits. The reconciled meaning is that the outbox record commits atomically with the Payment transition; Kafka publication occurs later.
2. Technical §9.5 abbreviates webhook reception as verification followed by storage. The reconciled design stores every bounded receipt and verification outcome before the final HTTP response, while only a verified canonical event can create processing work.
3. Technical §9.2 does not state how a consumed command survives a crash before the Provider call. The reconciled design commits inbox plus Provider work before the network boundary.
4. Inbox identity protects duplicate delivery of one message but does not alone reconcile the same final Provider fact observed through response, status query, and webhook. ADR-021 adds Payment-owned accepted-final-result application evidence and terminal-result validation without changing `PaymentStatus`.
5. Technical §12.2 shows an aspirational `applications/core-platform` directory. Repository evidence and the approved task direction keep Core at the root and add only `applications/provider-simulator`.

The absent ADR-004, ADR-005, ADR-006, and ADR-009 files are not an authority issue. Their decisions are accepted in the Technical Specification. Slice 0 restores repository traceability through retrospective records without claiming to recover original artifacts or introducing new decisions.

## Scope

Included:

- Kafka with at-least-once delivery;
- a generic `messaging` module and schema;
- transactional outbox, consumer inbox, dead-letter, claim, and lease records;
- versioned JSON and JSON Schema contracts;
- Payment-associated immutable Payment Attempts;
- Payment-owned accepted-final-result application evidence;
- Provider-owned durable work, recovery, interaction, result, and webhook evidence;
- atomic initial Payment submission and automatic safe retry creation;
- Kafka outbox publisher and inbox-backed consumers;
- separate Provider Simulator Gradle/Spring Boot application and database;
- HMAC-signed payment submission, status query, and webhooks;
- explicit timeouts, bounded retry, circuit breaker, bulkhead, ambiguity recovery, and dead-letter handling;
- OpenTelemetry propagation, Prometheus metrics, initial Grafana dashboards, and operational runbooks; and
- PostgreSQL, Kafka, Provider, contract, concurrency, crash, recovery, and end-to-end verification.

Excluded:

- Keycloak, identity, memberships, permissions, merchant scope, and authorization;
- Reversal aggregates, transitions, attempts, and postings;
- settlement files, reconciliation, cases, corrections, and formal approval chains;
- a polished frontend or authenticated scenario-management UI;
- public manual replay or sensitive administrative operations;
- Kubernetes, Terraform, AWS deployment, and portfolio-release hardening;
- applied AI;
- Redis unless a separately approved requirement demonstrates a concrete Release 0.2 need;
- moving the existing Core source tree; and
- end-to-end exactly-once delivery claims.

## Approved design input

The accepted design is in ADR-021. Implementation must not substitute framework defaults for its retry counts, timeout values, lease duration, HMAC canonicalization, webhook outcomes, event envelope, topic names, partition key, or final-result matrix.

The planned durable flow is:

```text
Payment submission transaction
  -> Payment Attempt + PROCESSING + SubmitPaymentToProvider outbox
  -> Kafka at-least-once
  -> Provider command inbox + durable work transaction
  -> Provider HTTP outside transaction
  -> Provider evidence + ProviderResultObserved outbox transaction
  -> Kafka at-least-once
  -> Payment result inbox + accepted result + lifecycle outbox transaction
       -> SUCCESS joins existing CompletePaymentAfterProviderSuccess
       -> DECLINED/definitive PERMANENT_FAILURE applies Payment.fail()
       -> non-final categories leave Payment PROCESSING
```

The allowed module dependency graph is:

```text
Provider -> messaging::api
Payment  -> provider::api
Payment  -> messaging::api
```

For safe retry, `SUBMISSION` work enters `WAITING_RETRY_REQUEST`; it never repeats the Provider call. A fenced transaction persists one stable Provider retry request and appends `PaymentSubmissionRetryRequested` to `ledgerops.payment.commands.v1`. Payment consumes the command through an inbox-backed transaction and creates the next attempt and `SubmitPaymentToProvider`. Provider never calls `payment::api` or queries Payment.

## Assumptions, risks, and dependencies

- Dependency: ADR-021 must become `Accepted` and the Technical Specification must be revised before production implementation.
- Dependency: Release 0.1 and its 224-test baseline remain green throughout Release 0.2.
- Dependency: The Provider Simulator supports stable idempotency and status lookup by the Payment-derived key.
- Assumption: One Core PostgreSQL database continues to support joined cross-module application transactions through published APIs.
- Assumption: The local/test Kafka topology can prove at-least-once delivery and crash recovery without claiming production scale.
- Risk: An event can be published more than once after Kafka acknowledgement. Mitigation: stable message IDs, inbox uniqueness, Provider-work uniqueness, final-result evidence, terminal-state checks, and ADR-020 replay.
- Risk: Provider acceptance can occur before Core persists the response. Mitigation: stable Provider idempotency key and status recovery before any resubmission.
- Risk: Generic messaging can absorb business semantics. Mitigation: generic envelopes, stores, leases, and failure metadata only; business modules own payload schemas and effects.
- Risk: Retry policies can create duplicate or stuck outcomes. Mitigation: explicit ADR limits, deterministic scheduling, `UNKNOWN` recovery, durable `UNRESOLVED` evidence, and no false final state.
- Risk: HMAC verification can differ across clients. Mitigation: raw-body hashing, exact canonical bytes, contract fixtures, constant-time comparison, and cross-application compatibility tests.
- Risk: Release 0.2 can drift into Release 0.3 administration and authorization. Mitigation: no public replay/scenario controls, no Reversal, no settlement, and explicit architecture tests.
- Risk: Multi-project conversion can disrupt the completed root application. Mitigation: retain Core at root and add only the Provider Simulator subproject.

## Ordered implementation slices

Each vertical slice has one implementation owner and must deliver an independently reviewable, observable, tested capability. Do not create disconnected Payment, Messaging, and Provider foundations before the first durable workflow.

### Slice 0 — documentation and ADR acceptance

Status: Completed

Authority: Product PRV-01–PRV-05 and BR-05–BR-06; Technical §§7, 9, 14.2, and 16; ADR-016 and ADR-020.

- Scope: Review ADR-021, create the four retrospective ADR records, approve exact semantics, revise the Technical Specification, and synchronize controlled supporting documentation.
- Exclusions: Production code, tests, migrations, dependencies, Kafka, Provider Simulator, and infrastructure.
- Domain and persistence: Document exact ownership, module dependencies, identities, transactions, and failures only.
- Idempotency and concurrency: Fix outbox, inbox, consumer-failure, attempt, retry-request, Provider result, work, and webhook identities.
- Observability: Fix metric names, bounded labels, trace propagation, and dashboard scope.
- Verification: Run cross-document searches, link and formatting checks, DOCX render inspection, and semantic diff review.
- Completion: ADR-021 is `Accepted`, retrospective ADR records exist, Technical Specification is revised, and this plan becomes `Active`.

### Slice 1 — durable Payment submission

Status: Completed

Authority: Product PAY-03, PRV-02, BR-01, BR-05, BR-12, and BR-13; Technical §§4.3–4.4, 5.1, 5.3, 5.7, 6, 7.5, and 9.2; accepted ADR-021.

- Scope: Add immutable Payment Attempts, the minimal `messaging` API/schema/outbox, and the Payment-owned `APPROVED -> PROCESSING` use case that appends `SubmitPaymentToProvider`.
- Exclusions: Kafka publication, Provider module, external calls, retry, result handling, inbox, and public lifecycle endpoints.
- Domain behavior: Use `Payment.startProcessing()`; sequence starts at 1; attempts are immutable and external to the aggregate; every attempt uses `providerId = SIMULATOR`; hash exactly the approved canonical request-intent fields.
- Persistence and transactions: Lock Payment and atomically persist sequence-1 attempt, expected-version Payment update, and outbox record. Enforce attempt uniqueness and `UNIQUE (producer_name, deduplication_key)`.
- Idempotency and concurrency: Use `payment-submission:<attemptId>`; compare the canonical business-content hash; equivalent repeats return the original outbox/message ID; changed content or partial evidence is a typed consistency error.
- Typed failures and observability: Cover missing/wrong-state Payment, version conflict, constraint failure, append conflict, rollback, and bounded submission logs/metrics.
- Verification: PostgreSQL/Testcontainers migration, atomic rollback, replay, concurrent submission, outbox deduplication, immutable attempt, tenant isolation, and Modulith tests.
- Completion: One operation yields one `PROCESSING` Payment, one attempt, and one durable command; a failure yields none.

Completed evidence:

- Added the immutable Payment-owned `PaymentAttempt` model with sequence 1, `providerId = SIMULATOR`, the Payment-derived Provider idempotency key, and the exact canonical request-intent hash.
- Added forward migration V8 for Payment Attempts and the minimal Messaging-owned `PENDING` outbox. PostgreSQL enforces tenant/Payment ownership, attempt sequence uniqueness, the closed Provider catalog, typed producer names, business deduplication, JSON-object payload validation, and immutable attempt and outbox business content.
- Added the internal Payment-owned submission transaction. It locks and validates the tenant before locking the Payment, persists the attempt, applies `Payment.startProcessing()`, performs the expected-version update, and appends `SubmitPaymentToProvider` atomically.
- Equivalent replay returns the original attempt, outbox, and message identities. Partial or mismatched evidence raises a typed consistency failure and is never repaired or normalized.
- Added canonical JSON Schema fixtures for SAR, JPY, an optional compatible payload extension, and invalid content. Canonical payload bytes survive PostgreSQL persistence unchanged.
- PostgreSQL/Testcontainers tests cover successful submission, suspended-tenant serialization, tenant isolation, replay, concurrent submission, concurrent outbox deduplication, rollback at Payment/outbox failures, typed concurrency and persistence failures, immutable evidence, database constraints, canonical hashing, and Modulith boundaries.
- Kafka, Provider work, external calls, inbox processing, result handling, retry, and webhooks remain unimplemented and belong to later slices.

### Slice 2 — Kafka command delivery

Status: Completed

Authority: Technical §§4.4, 6, 7.1–7.8, 9.2, 11, and 13; accepted ADR-004, ADR-005, ADR-006, and ADR-021.

- Scope: Add Kafka contracts, outbox claiming/publication, consumer inbox, consumer-failure/dead-letter records, Provider module work persistence, and the inbox-backed provider-command consumer.
- Exclusions: Provider HTTP, result application, webhooks, safe retry, and manual replay.
- Domain behavior: At-least-once publication is explicit; duplicate commands create one Provider work item and no new Payment Attempt.
- Persistence and transactions: Claim due `PENDING`, due `RETRYABLE`, and expired `CLAIMED` rows with `FOR UPDATE SKIP LOCKED`; assign `leaseOwner`, a new `leaseToken`, and `leaseExpiresAt`; publish outside database transactions; commit inbox and tenant-owned Provider work together before acknowledgement.
- Idempotency and concurrency: Fence every lease renewal and mutable completion with the current token. Keep `messageId` stable; allow only typed producer names `payment` and `provider`; use `(consumer_name, message_id)` with status `PROCESSED` or `DEAD`, `(producer_name, deduplication_key)` for outbox, dead-letter uniqueness by `outboxId`, `(consumer_name, message_id)`, or transport identity `(consumer_name, topic, partition, offset)`, and Provider work business uniqueness.
- Typed failures and observability: Cover Kafka outage, lease loss, transport-invalid envelope, unsupported schema, malformed payload, five-delivery poison handling, dead letters, publisher backlog, lag, duplicate count, correlation, and causation.
- Verification: Kafka/PostgreSQL Testcontainers prove outage recovery, acknowledgement/mark crash duplication, expired-claim recovery, stale-token rejection, the closed producer catalog and implemented submission mapping, duplicate commands, durable work, valid-envelope versus transport-invalid dead letters, terminal inbox status, poison handling, and no Provider-to-Payment dependency. Later producer/message mappings remain in their scheduled slices.
- Completion: A committed command reaches one recoverable Provider work item despite Kafka outage, redelivery, or process failure.

Completed evidence:

- Added the version-1 closed JSON envelope, Payment-keyed `ledgerops.provider.commands.v1` publication, `acks=all`, and idempotent Kafka-producer configuration.
- Expanded the Messaging schema with due/expired claiming through `FOR UPDATE SKIP LOCKED`, 30-second fenced leases, deterministic bounded retry, `PUBLISHED`/`RETRYABLE`/`DEAD` outcomes, and publication dead letters unique by outbox ID.
- Added inbox status exactly `PROCESSED` or `DEAD`, separate durable failure counting through five deliveries, immediate unsupported-version dead lettering, and transport dead letters unique by consumer/topic/partition/offset.
- Added the acyclic Provider module with one tenant-owned, duplicate-safe `SIMULATOR` `SUBMISSION` work item per tenant/attempt/work type. The consumer commits inbox and Provider work atomically and performs no Provider HTTP call.
- Added the ADR-021 outbox publication, pending, oldest-age, inbox-duplicate, and consumer-dead measures. End-to-end trace propagation, the exact Kafka consumer-lag measure, dashboards, and runbooks remain Slice 7 work.
- PostgreSQL and Kafka Testcontainers evidence covers stable publication identity, expired-lease recovery, stale-token rejection, attempt exhaustion, duplicate publication after the Kafka-acknowledgement crash window, duplicate commands, atomic inbox/work rollback, poison counting, and transport-invalid evidence.
- Provider execution, Provider interaction/result evidence, and the separate Provider Simulator remain unimplemented and begin in Slice 3.

### Slice 3 — Provider execution

Status: Completed

Authority: Product PRV-01–PRV-05, DEV-01, DEV-03–DEV-04, and BR-05–BR-06; Technical §§3, 8.1, 8.6, 9.1–9.4, 9.6, 11–13; accepted ADR-009 and ADR-021.

- Scope: Add the separate Provider Simulator/database, direction-specific HMAC contracts, signed submission/status endpoints, Provider execution worker, resilience policies, immutable tenant-owned evidence, and `ProviderResultObserved` outbox.
- Exclusions: Payment result application, webhooks, retries, Release 0.3 controls, and settlement files.
- Domain behavior: Preserve exact result categories and `RetryDisposition`; distinguish `SUBMISSION` and `STATUS_QUERY` work; ambiguous transmission becomes `UNKNOWN`; only proven no-acceptance evidence is `SAFE_TO_RESUBMIT`; calls occur outside database transactions.
- Persistence and transactions: Claim durable work with a new fencing token, commit, call Provider, then atomically persist interaction/result evidence and result outbox only with the current token. `WAITING_RETRY_REQUEST` submission work never repeats the Provider call. Enforce `(tenant_id, provider_id, provider_result_id)`.
- Idempotency and concurrency: Use only Provider `SIMULATOR`; create the Simulator idempotency record when accepting a request into its durable transaction; reuse the Payment-derived key; use `provider-result:<tenantId>:<providerId>:<providerResultId>`; equivalent repeats return the original Provider transaction and retain one result message ID.
- Typed failures and observability: Cover signature/key direction, timeout, ambiguity, circuit, bulkhead, lease, evidence conflict, Provider latency/categories, and backlog.
- Verification: Cross-application HMAC fixtures, transaction probes, separate-database isolation, deterministic scenarios, idempotency-record timing, no duplicate Provider transaction, status-query resubmission prohibition, stale-work-token rejection, result deduplication, and Provider Testcontainers.
- Completion: Provider execution produces durable evidence and one recoverable result command without holding a database transaction during the network call.

Implemented evidence:

- Added `applications/provider-simulator` as a separate Spring Boot subproject with its own Flyway-managed PostgreSQL schema. The Simulator has no Core database dependency and persists its idempotency record before returning an accepted Provider transaction.
- Added signed `POST /provider/v1/payments` and `POST /provider/v1/payment-status-queries` contracts using the exact direction-specific HMAC canonical bytes. A shared golden fixture proves independent Core signing and Simulator verification.
- Added fenced `SUBMISSION` and `STATUS_QUERY` execution. Core claims and commits work, performs HTTP with no active database transaction, and then atomically persists immutable interaction/result evidence plus one `ProviderResultObserved` outbox record.
- Added exact `ProviderResultCategory` and `RetryDisposition` models, database constraints, stable Provider-result deduplication, and conservative crash handling. An expired possibly transmitted submission becomes `UNKNOWN` recovery evidence and is never called again blindly.
- Added the approved 1-second connection, 3-second read, and 5-second total limits; exact circuit-breaker and bulkhead policies; and fenced deferral that does not consume an execution count when no call occurs.
- Added bounded Provider HTTP duration, result-category, timeout, ambiguity, retry-backlog, recovery-backlog, and circuit-state metrics without business identifiers as labels.
- PostgreSQL and Simulator tests verify separate schemas, durable acceptance, idempotent replay/conflict, timeout recovery, result/outbox uniqueness, duplicate observations, lease fencing, safe-retry proof, no network call in a transaction, HMAC compatibility, and version-1 result contracts.
- Slice 3 deliberately excluded Payment result application and later recovery/webhook work. Slice 4 now completes result application; intentional retry/status scheduling, webhooks, dashboards, and runbooks remain Slices 5–7.

### Slice 4 — Provider result application

Status: Completed

Authority: Product PAY-03, PRV-02–PRV-04, BR-05, and §§10–11; Technical §§5.3, 5.6–5.7, 7.6, 9.2–9.3, 13, and 17.2; ADR-016, ADR-020, and accepted ADR-021.

- Scope: Add Payment's result consumer, `provider::api` evidence verification, accepted-final-result evidence, `FAILED` handling, existing ADR-020 `SUCCESS`, and lifecycle outbox.
- Exclusions: A second success path, public completion, retry, webhook reception, Reversal, and correction.
- Domain behavior: Apply the exact result matrix; terminal states never regress; non-final results leave Payment `PROCESSING`.
- Persistence and transactions: Commit inbox, accepted final result, Payment/ADR-020 Ledger effect, and lifecycle outbox once. Provider evidence remains read-only through `provider::api`.
- Idempotency and concurrency: Use `payment-final:<paymentId>`, Payment locking, one accepted final result, Ledger source uniqueness, stable outbox message ID, and exact replay verification.
- Typed failures and observability: Cover missing/mismatched evidence, lifecycle/final conflicts, Ledger inconsistency, rollback, duplicate financial effect, and result traces/metrics.
- Verification: PostgreSQL/Kafka tests prove duplicate and conflicting results, exact success posting, failure path, no terminal regression, full rollback, concurrent result handling, and no public completion endpoint.
- Completion: Durable Provider evidence causes at most one terminal Payment transition and one ADR-020 financial effect.

Implemented evidence:

- Added the inbox-backed `payment-provider-result-consumer-v1` for `ProviderResultObserved` on `ledgerops.provider.results.v1`, including valid-envelope, transport-invalid, unsupported-version, bounded-failure, and permanent-consistency handling.
- Added Payment-owned immutable accepted-final-result evidence through forward migration V11. The table is unique by `(tenant_id, payment_id)`, preserves the exact attempt relationship, and rejects update or deletion.
- Added neutral `provider::api` evidence verification and exact tenant, Payment, attempt, Provider, result, disposition, reference, origin, and observation matching before any Payment effect.
- Implemented every approved result category: non-final results leave Payment `PROCESSING`; `DECLINED` and definitive `PERMANENT_FAILURE` use `Payment.fail()`; `SUCCESS` invokes the unchanged ADR-020 completion service.
- Added `PaymentCompleted` and `PaymentFailed` version-1 JSON Schemas and the exact shared `payment-final:<paymentId>` outbox identity.
- PostgreSQL and Kafka tests prove exact replay, changed-final conflict preservation, late non-final no-regression, tenant isolation, concurrent final delivery, one accepted result, one lifecycle event, and one exact Ledger effect.
- Forced missing-account, Ledger-entry, Payment-update, accepted-evidence, and lifecycle-outbox failures prove that inbox, accepted evidence, Payment, Ledger, and outbox effects roll back together.
- No retry scheduling, webhook reception, Reversal, correction, public completion endpoint, or Release 0.3 capability was added.

### Slice 5 — safe retry and ambiguity/status recovery

Status: Completed — 21 July 2026

Authority: Product PRV-02–PRV-04 and BR-05–BR-06; Technical §§7, 9.2–9.4, 11, and 13; accepted ADR-021.

- Scope: Add bounded Provider scheduling, status recovery, `PaymentSubmissionRetryRequested`, `ledgerops.payment.commands.v1`, and Payment's inbox-backed retry consumer.
- Exclusions: Blind retry after ambiguity, public/manual replay, Reversal retry, and casework.
- Domain behavior: `SUCCESS`, `DECLINED`, and definitive `PERMANENT_FAILURE` are `NOT_RETRYABLE`; `UNKNOWN`, `ACCEPTED`, and `PENDING` require status recovery; `TEMPORARY_FAILURE` is `SAFE_TO_RESUBMIT` only with durable proof that no Provider transaction was accepted or created, and otherwise requires status recovery. Exhaustion leaves Payment `PROCESSING`.
- Persistence and transactions: Only `SAFE_TO_RESUBMIT` evidence moves `SUBMISSION` work to `WAITING_RETRY_REQUEST`. When due, that work is claimed directly with a new fencing token and never passes through Provider-call `RETRYABLE`. The worker verifies its token and atomically creates or finds the immutable Provider retry request, appends `PaymentSubmissionRetryRequested`, and completes the original work. Payment later verifies the authoritative disposition through `provider::api`, locks Payment, and commits inbox, next `SIMULATOR` attempt, and `SubmitPaymentToProvider` once.
- Idempotency and concurrency: Provider enforces `UNIQUE (tenant_id, provider_evidence_id)`, generates and persists `retryRequestId` once, and uses producer `provider` with `payment-retry:<retryRequestId>`. Payment persists one retry-application record unique by `(tenant_id, retry_request_id)`. Repeats return the linked records and commands; changed content is a typed consistency error.
- Typed failures and observability: Cover stale/mismatched evidence, wrong status, attempt limit, lease loss, recovery exhaustion, retry/recovery backlog, and unresolved evidence.
- Verification: Deterministic Clock/scheduler, exact category/disposition matrix, no-acceptance proof, status-query-only HTTP retry, no original-submission re-execution, fenced retry-request transaction, crash-stable `retryRequestId`, exact limits/jitter, concurrent retry commands, stable result, no module cycle, no blind resubmission, and no false `FAILED` state.
- Completion: Safe retry creates one immutable attempt through the acyclic messaging flow; ambiguous and exhausted outcomes remain durable.

Implementation evidence:

- Provider scheduling now creates bounded `STATUS_QUERY` work for ambiguous outcomes and never returns `SUBMISSION` work in `WAITING_RETRY_REQUEST` to the Provider-call path.
- Due safe-retry work is claimed with a new fencing token and atomically creates one immutable Provider retry-request record, appends `PaymentSubmissionRetryRequested`, and completes the original work.
- Payment consumes the retry command through `payment-retry-command-consumer-v1`, verifies authoritative `SAFE_TO_RESUBMIT` evidence through `provider::api`, locks the `PROCESSING` Payment, and atomically creates the next immutable `SIMULATOR` attempt and `SubmitPaymentToProvider` command.
- V12 enforces the three-attempt limit, stable retry-request and retry-application identities, immutable records, tenant ownership, and no cross-schema foreign key between Payment and Provider.
- Proven pre-transmission failures receive one deterministic 200 ms ±20% low-level retry without creating another Payment Attempt; once consumed, an uncertain failure enters status recovery.
- PostgreSQL, Kafka, concurrency, rollback, contract, deterministic-schedule, and Modulith tests prove stable replay, one next attempt, one command, status-query-only recovery, no original-submission re-execution, and no Payment status invented for recovery.

### Slice 6 — signed webhook reception and asynchronous processing

Status: Completed — 21 July 2026

Authority: Product PRV-01–PRV-04, BR-01, BR-05, and §11; Technical §§8.6, 9.5–9.6, 11, and 13; accepted ADR-021.

- Scope: Add signed reception, bounded platform rejection evidence, Provider-scoped unattributed evidence, mapped tenant-owned receipts/canonical events, Provider-owned identity mapping, leased asynchronous processing, and Stage A result publication.
- Exclusions: Merchant webhook testing under DEV-02, Payment-table queries, `payment::api`, public replay, and settlement webhooks.
- Domain behavior: Unauthenticated requests create no Provider receipt or work. Authenticated malformed or unmapped events remain unattributed. Resolve tenant/Payment/attempt only from Provider-owned mappings; never trust tenant from webhook input; preserve duplicate, invalid, out-of-order, and conflicting evidence.
- Persistence and transactions: Enforce `(tenant_id, provider_id, provider_event_id)` after mapping; persist the exact rejection, unattributed, or tenant-owned evidence before response; process only mapped work asynchronously with fenced leases.
- Idempotency and concurrency: Same identity/hash returns `202` without new work; different hash returns `409`; resulting facts use Provider result and outbox business identities.
- Typed failures and observability: Cover the exact `401`, `400`, `202`, `409`, and `413` attribution matrix, key direction, timestamp, missing mapping, backlog, duplicates, conflicts, and trace propagation.
- Verification: HMAC canonical bytes, separate keys, unauthenticated no-receipt behavior, malformed unattributed evidence, unmapped `202`, identity mapping, replay-window boundaries, duplicate/conflicting/out-of-order delivery, fenced async recovery, tenant isolation, and one Payment/Ledger effect.
- Completion: Signed Provider webhooks are durable and duplicate-safe without implementing merchant webhook testing or introducing a module cycle.

Implementation evidence:

- Core accepts only bounded signed webhook bodies at `POST /internal/provider/v1/webhooks`. The controller reads at most 256 KiB plus one detection byte before authentication or JSON parsing.
- Reception persists the exact platform rejection, authenticated-invalid, unmapped, mapped, duplicate, or conflict evidence before returning `401`, `400`, `202`, `409`, or `413`. Webhook payload fields never establish tenant identity.
- Provider-owned mappings resolve tenant, Payment, and Payment Attempt identity. Mapped canonical events and receipts are tenant-owned and unique by `(tenant_id, provider_id, provider_event_id)`; duplicate content creates evidence without new work, and changed content creates durable conflict evidence.
- Asynchronous webhook processing uses recoverable fenced leases. Only the current lease token can commit immutable interaction/result evidence, schedule status recovery, append `ProviderResultObserved`, or complete the work.
- The separate Provider Simulator durably creates signed webhook deliveries in its own database and supports duplicate, delayed, missing, out-of-order, invalid-signature, and conflicting-result test scenarios without accessing the Core database.
- Contract, PostgreSQL, HTTP, Kafka, concurrency, crash-recovery, and end-to-end tests prove exact HMAC bytes, key direction, timestamp boundaries, tenant isolation, duplicate/conflicting evidence, Stage A atomicity, terminal-state no-regression, and one ADR-020 Payment/Ledger effect.
- DEV-02 merchant webhook testing, public replay, settlement webhooks, dashboards, and runbooks remain excluded or scheduled for Slice 7 as specified.

### Slice 7 — release hardening and gate

Status: Completed — 22 July 2026

Authority: Product PRV-05, DEV-01–DEV-04, §§10–12; Technical §§7.7, 11, 13, 14.2, 17, and Appendix C; accepted ADR-021.

- Scope: Complete poison/dead-letter handling, OpenTelemetry, Prometheus, Grafana, end-to-end failure scenarios, demo, contracts, runbooks, traceability, and release evidence.
- Exclusions: Public replay, polished product UI, Keycloak, Reversal, settlement/reconciliation, Redis, Kubernetes, Terraform, AWS, and applied AI.
- Domain behavior: Add no new lifecycle behavior; retain all limits and no-false-result rules.
- Persistence and transactions: Prove consumer-failure counting, exact inbox statuses, all three dead-letter identities, transport-invalid immediate commit, terminal inbox/dead-letter commit, fenced lease recovery, clean migration install/upgrade, and no telemetry as transactional truth.
- Idempotency and concurrency: Exercise duplicate publication, command, result, webhook, retry, crash, backlog, and recovery scenarios against final PostgreSQL state.
- Typed failures and observability: Demonstrate every outage, poison, dead, ambiguity, webhook conflict, rollback, bounded metric, dashboard, and alert-to-runbook path.
- Verification: Run full Gradle checks, PostgreSQL/Kafka/Provider Testcontainers, contract compatibility, migration, architecture, end-to-end, terminology, and diff checks.
- Completion: Every Release 0.2 criterion has executable evidence, requirement statuses are accurate, no blocker remains, and no later-release capability entered production.

Implementation evidence:

- OpenTelemetry trace context persists with outbox records, Kafka headers, Provider work, signed Provider HTTP requests, Simulator transactions, webhook deliveries, and mapped webhook work without entering HMAC input or metric labels. Executable span-tree tests prove that delayed outbox publication resumes the persisted parent, Provider command consumption and asynchronous webhook processing continue their published traces, and the Core Provider HTTP and Simulator webhook HTTP client spans propagate their child contexts. Invalid optional trace metadata is dropped and counted without poisoning business work.
- Prometheus exposes every ADR-021 bounded measure. Kafka lag covers all partitions, including partitions without a committed consumer offset. Retry and recovery gauges include leased in-flight work, and the financial-effect detector queries duplicate tenant-scoped `PAYMENT` source identities instead of exposing an unwired constant.
- Two provisioned Grafana dashboards cover messaging delivery and Provider operations. Eight validated Prometheus alerts link to the matching operations-runbook procedures.
- V14 adds immutable Core trace context without changing V1–V13. Simulator V3 adds the corresponding separate-database trace evidence without changing Simulator V1–V2.
- PostgreSQL Testcontainers proves a fresh V1–V14 installation and an in-place upgrade from the Release 0.1 V7 baseline while preserving existing tenant data.
- The local Compose topology, Provider contract, Provider-flow diagram, operations runbook, Release 0.2 demo, and destructive reset warning match the implemented runtime.
- The clean full test suite, full project checks, contract/schema checks, HMAC fixtures, OpenAPI parsing, dashboard JSON, Prometheus rules, links, migration audits, and scope searches pass at the release gate.

## Expected files

The exact file list is finalized at the start of each slice. The planned ownership is:

- Add under root Core: `src/main/java/com/ledgerops/messaging/{api,application,domain,infrastructure}`.
- Add under root Core: `src/main/java/com/ledgerops/provider/{api,application,domain,infrastructure}`.
- Add Payment Attempt/result-application files under the existing Payment module.
- Add forward migrations after V7 for Payment, messaging, and Provider schemas. Never edit V1–V7.
- Add versioned JSON Schemas under `packages/event-contracts/v1` and Provider HTTP schemas under `packages/provider-contracts/v1`.
- Add the separate Gradle/Spring Boot project at `applications/provider-simulator` with its own migrations and configuration.
- Add PostgreSQL/Kafka/Provider Testcontainers support and focused tests under `src/test` and the Simulator test tree.
- Add Release 0.2 diagrams under `docs/architecture/diagrams`.
- Add runbooks under `docs/runbooks`.
- Add the Release 0.2 demo under `docs/demo`.
- Modify `settings.gradle.kts` and Gradle build files only in their scheduled implementation slices.
- Modify OpenAPI/provider contract indexes, README, AGENTS, traceability, and this plan only as evidence becomes true.
- No change expected to Product Definition v1.6 unless implementation reveals a product-level conflict.
- Preserve the existing `CompletePaymentAfterProviderSuccess`, `ledger::api`, Ledger migrations, and ADR-020 tests except for additive integration around them.

## Acceptance and verification

| Acceptance condition | Required evidence | Planned command | Status |
|---|---|---|---|
| Submission, attempt, PROCESSING, and command outbox commit atomically | PostgreSQL integration and forced-failure tests | `./gradlew test --tests '*PaymentSubmission*' --console=plain` | Passed — Slice 1 |
| Every Payment Attempt uses SIMULATOR and the exact request-intent hash | Canonical JSON fixtures, SHA-256 vectors, and unsupported-provider tests | Payment Attempt and contract suites | Passed — Slice 1 |
| Rollback leaves no attempt, transition, or message | Injected persistence failures and final-row assertions | Same focused suite | Passed — Slice 1 |
| Equivalent business outbox appends reuse one stable message | PostgreSQL uniqueness, content-hash, and replay tests for all four logical keys | Messaging and producing-module suites | Passed — all four identities, including `payment-retry:<retryRequestId>` |
| Changed content under one outbox identity fails | Typed consistency-error and unchanged-row assertions | Messaging integration suite | Passed — implemented identity |
| Producer names and mappings are closed and exact | Typed API tests, four producer/key/type mappings, and shared Payment-final conflict test | Messaging contract and integration suites | Passed — all four producer/key/type mappings |
| Kafka outage preserves committed outbox work | Kafka/PostgreSQL Testcontainers outage/recovery test | `./gradlew test --tests '*ProviderCommandDeliveryIntegrationTests' --console=plain` | Passed — Slice 2 |
| Publisher crash after Kafka acceptance duplicates safely | Failpoint test plus inbox/business final state | Same focused suite | Passed — Slice 2 |
| Duplicate command creates one Provider work item | Kafka consumer concurrency test | `./gradlew test --tests '*ProviderCommandDeliveryIntegrationTests' --console=plain` | Passed — Slice 2 |
| Provider call runs outside database transaction | Transaction-probe integration test | `./gradlew :test --tests '*ProviderHttpBoundaryTests' --console=plain` | Passed — Slice 3 rejects HTTP with an active transaction |
| Duplicate Provider results create one Payment/Ledger effect | Kafka/PostgreSQL coordinated result tests | `./gradlew :test --tests '*ProviderResult*' --console=plain` | Passed — Slice 4 result delivery and Slice 6 webhook-origin results converge on one accepted effect |
| Duplicate webhooks create one Payment/Ledger effect | Signed HTTP plus Kafka/PostgreSQL end-to-end test | `./gradlew :test --tests '*ProviderWebhook*' --console=plain` | Passed — Slice 6 |
| Out-of-order/conflicting finals do not regress state | Result-matrix and dead-letter tests | Result and webhook suites | Passed — Slices 4 and 6 preserve the accepted final effect and retain conflicting evidence |
| Timeout produces UNKNOWN and status recovery | Bounded real-HTTP timeout and durable-work tests | Provider execution suite | Passed at Provider boundary — Slice 3 |
| UNKNOWN is never blindly resubmitted | Work/attempt count and Provider-call assertions | Provider execution suite | Passed — UNKNOWN creates bounded status-query work only |
| Bounded safe retries create immutable attempts | Clock/scheduler plus Payment Attempt tests | Retry/recovery suite | Passed — maximum three attempts, exact delay bounds, immutable attempt evidence |
| Retry command flow remains acyclic and idempotent | Modulith graph, inbox, evidence, locking, and concurrent `retryRequestId` tests | Retry/recovery and architecture suites | Passed — Slice 5 |
| Intentional retry never re-executes original submission work | `WAITING_RETRY_REQUEST`, work-type, Provider-call-count, fenced transaction, and next-attempt assertions | Provider retry/recovery suite | Passed — original execution count remains one |
| Retry request identity survives scheduling and crashes | `(tenant_id, provider_evidence_id)` concurrency, stable `retryRequestId`, and stable outbox tests | Provider PostgreSQL and recovery suites | Passed — immutable unique retry request and outbox identity |
| Exhaustion remains durable without false final state | Work status, dead evidence, and Payment final-state assertion | Retry/recovery suite | Passed at the Slice 5 boundary — Provider work becomes `UNRESOLVED`; Payment remains `PROCESSING` |
| SUCCESS uses exact ADR-020 posting | Existing plus outer-result transaction tests | Payment completion/result suites | Passed — Slice 4 invokes the unchanged boundary and verifies the exact two-entry posting and replay |
| Ledger or Payment failure still rolls back completion | Existing ADR-020 failpoints with inbox/outbox assertions | Payment completion/result suites | Passed — Slice 4 verifies missing-account, Ledger insertion, Payment update, accepted-evidence, and lifecycle-outbox rollback |
| Inbox and business effect commit together | PostgreSQL/Kafka failure tests | Consumer suites | Passed for Provider command work, Payment result application, and Payment retry-command application |
| Valid-envelope unsupported/poison events dead-letter safely | Rollback, separate failure-count transaction, failure-five terminal transaction, immediate unsupported-version, and malformed-payload tests | Messaging consumer suite | Passed — Slice 2 PostgreSQL/Kafka evidence |
| Transport-invalid envelopes bypass inbox safely | No-messageId parsing cases, immediate transport dead letter, exact topic/partition/offset identity, bounded evidence, and post-commit acknowledgement | Messaging transport suite | Passed — Slice 2 PostgreSQL/Kafka evidence |
| Claim leases recover crashed workers/publishers | Coordinated lease-expiry tests with injected Clock | Messaging and Provider suites | Passed for outbox publisher and Provider work through Slice 3 |
| Stale lease holders cannot mutate reclaimed records | New-token claim, renewal, and every terminal/wait-state CAS test | Messaging and Provider concurrency suites | Passed for outbox and Provider evidence/state mutations through Slice 3 |
| Inbox and dead-letter identities are exact | `PROCESSED`/`DEAD`, outboxId, consumerName/messageId, and consumerName/topic/partition/offset constraint tests | Messaging PostgreSQL suite | Passed — Slice 2 |
| RetryDisposition enforces exact retry safety | Category matrix, database constraint, durable no-acceptance proof, authoritative API verification, and status-query prohibition | Provider and retry/recovery suites | Passed — Provider constraints plus Payment-side authoritative evidence verification |
| Webhook attribution follows the exact authentication matrix | `401` platform-only, malformed `400`, unmapped `202`, and mapped duplicate/conflict tests | Webhook suite | Passed — Slice 6, including bounded `413` handling and direction/timestamp checks |
| Every mapped Core business record is tenant-owned | Migration constraints, unattributed-evidence isolation, and repository tests | PostgreSQL integration suites | Passed through Slice 6 — mapped webhook records are tenant-owned; platform and unmapped evidence cannot create tenant work |
| Simulator cannot access Core database | Separate application build, datasource, migration tree, and PostgreSQL integration assertion | Simulator test task | Passed — Slice 3 separate subproject and PostgreSQL Testcontainer |
| Correlation, causation, and trace context propagate | HTTP/Kafka/Provider/webhook span assertions | Observability end-to-end suite | Passed — Slice 7 proves persisted-parent, Kafka producer/consumer, and Provider HTTP client span lineage; propagates W3C context across delayed boundaries; and keeps trace headers outside HMAC input |
| Metrics and dashboards expose required signals | Meter-registry, detector, backlog-state, and dashboard-schema tests | Observability suite | Passed — Slice 7 provides executable duplicate-source detection, active retry/recovery backlog accounting, partition-complete Kafka lag, two Grafana dashboards, and eight runbook-linked alerts |
| Modulith/ArchUnit prohibit forbidden access | Module verification and dependency rules | `./gradlew check --console=plain` | Passed — Release 0.2 gate |
| No Release 0.3+ capability is introduced | Dependency/source/scope searches and diff review | Repository audit commands | Passed — Release 0.2 gate |
| All existing and Release 0.2 tests pass | Full suite | `./gradlew clean test --console=plain` | Passed — Release 0.2 gate |
| All project checks pass | Full check | `./gradlew check --console=plain` | Passed — Release 0.2 gate |

### Required release-gate commands

Run the exact available tasks after the multi-project build exists. At minimum:

```bash
./gradlew test --console=plain
./gradlew check --console=plain
git diff --check
```

Also validate every JSON Schema against valid and invalid fixtures, render and inspect any revised DOCX, start the local Release 0.2 topology from documented commands, execute the release demo, inspect Prometheus targets and Grafana dashboard JSON, and review the final diff for unrelated changes.

## Failure and recovery cases

- Business transaction commits but Kafka is unavailable: outbox remains due and publishes after recovery.
- Kafka accepts but publisher crashes before marking: message can be published again and consumers deduplicate it.
- An expired outbox or Provider work claim is reclaimed: the new claim receives a new token and stale holders cannot mutate state.
- Consumer crashes before commit: inbox and business work roll back and Kafka redelivers.
- Consumer crashes after commit before acknowledgement: duplicate delivery finds inbox and performs no work.
- Provider command commits but worker crashes: durable work survives and an expired lease recovers it.
- Provider accepts but Core loses response: the durable Simulator idempotency record and status query recover the original transaction; no blind submission or second Provider transaction occurs.
- Safe resubmission becomes due: fenced `WAITING_RETRY_REQUEST` work writes one stable retry request and command, completes the original submission work, and never repeats its Provider call.
- Retry-request transaction rolls back or its worker crashes: uniqueness by tenant/evidence and the persisted `retryRequestId` recover the same request and outbox record.
- Provider returns non-final category: Payment remains `PROCESSING` and recovery is durable.
- Retry or status limit is exhausted: Payment remains `PROCESSING`; Provider work becomes `UNRESOLVED` and visible.
- Provider evidence append or result outbox append fails: Stage A rolls back and work remains recoverable.
- Provider result lacks matching evidence: Stage B does not change Payment and bounded handling ends in dead-letter evidence.
- Payment result transaction fails in Ledger, Payment persistence, inbox, accepted-result, or outbox: all Stage B effects roll back.
- Duplicate or conflicting final outcome arrives: exact replay returns existing result; conflict is preserved and never overwrites it.
- Unauthenticated webhook arrives: bounded platform rejection evidence remains durable, but no Provider receipt or work exists.
- Authenticated malformed or unmapped webhook arrives: exact unattributed evidence remains durable, but no tenant-owned work or lifecycle effect exists.
- Duplicate, out-of-order, or conflicting mapped webhook arrives: tenant-owned evidence remains durable and no invalid lifecycle or Ledger effect occurs.
- Circuit opens or bulkhead is full: durable work is rescheduled without consuming a Payment Attempt when no call occurred.
- Business consumer fails: its inbox/business transaction rolls back, then a separate short transaction increments the unique consumer-failure record.
- Fifth poison delivery, permanently invalid payload, or unsupported contract version arrives in a valid envelope: the normal terminal inbox/dead-letter transaction commits before acknowledgement.
- Kafka record has no trustworthy `messageId`: no inbox or consumer-failure record is created; the unique transport dead letter commits before acknowledgement.

## Controlled documentation amendments

### Applied Technical Specification amendments

Technical Specification v1.6 applies these accepted semantic amendments:

1. Document control and revision history: add “Version 1.6 — Release 0.2 Provider, messaging, retry, webhook, simulator, and observability semantics reconciled through ADR-021 — Approved for Release 0.2 implementation.”
2. §4.3 Module ownership: add the exact acyclic direction `Provider -> messaging::api`, `Payment -> provider::api`, and `Payment -> messaging::api`. Prohibit Provider from depending on `payment::api` or querying Payment. Add Payment-owned accepted-final-result application evidence and preserve ADR-016 ownership.
3. §5.1 Primary aggregates: limit Release 0.2 Payment Attempts to Payments and `providerId = SIMULATOR`, define the immutable fields, sequence, and exact `requestIntentHash` input, prohibit an embedded attempt collection, and defer Reversal attempts to Release 0.3.
4. §5.6 Atomic Payment completion: append “The Release 0.2 Payment result transaction invokes the existing completion use case. Its inbox record, accepted-final-result evidence, exact ADR-020 Payment/Ledger effect, and PaymentCompleted outbox record commit once. No event without matching durable Provider evidence may invoke completion.”
5. §5.7 Idempotency and concurrency: add attempt uniqueness `(tenant_id, payment_id, sequence)`, Provider retry-request uniqueness `(tenant_id, provider_evidence_id)`, canonical outbox content hashing, exact typed producer names/mappings, outbox uniqueness `(producer_name, deduplication_key)`, inbox identity/status, consumer and transport dead-letter identities, consumer-failure identity `(consumer_name, message_id)`, Payment-derived Provider key, retry-request replay, result replay/conflict rules, and fenced claim leases.
6. §6.1–6.2: add `messaging.*` ownership and the new Payment/Provider records; retain the no-cross-schema-access and no-cross-schema-foreign-key rule.
7. Replace §7.5–7.7 with ADR-021's exact outbox states, candidate states, `FOR UPDATE SKIP LOCKED` claim sequence, lease fields, token fencing, canonical deduplication hash, typed producers, 10-attempt publication limit, inbox statuses, valid-envelope consumer handling, transport-invalid immediate dead letters, all dead-letter identities, five-delivery poison limit, immediate unsupported-version dead letter, and no exactly-once claim.
8. §7.8: name the six schema-version-1 contracts, including `PaymentSubmissionRetryRequested`, add `ledgerops.payment.commands.v1`, and require JSON Schema compatibility fixtures.
9. Replace §9.2's final sentence with: “PaymentCompleted or PaymentFailed is inserted into the transactional outbox in the same PostgreSQL transaction as the accepted Payment state change. Kafka publication occurs later and may repeat.” Add the durable command-work, `SUBMISSION`/`STATUS_QUERY` distinction, `WAITING_RETRY_REQUEST` transaction, stable Provider retry-request record, and two-stage result flow from ADR-021.
10. §9.3: retain exactly the seven categories; add the exact three-value `RetryDisposition`, category/disposition matrix, evidence-verification rule, terminal replay, and conflicting-final behavior.
11. Replace §9.4 defaults with ADR-021's exact attempt, status-query, backoff/jitter, lease, timeout, circuit-breaker, bulkhead, and exhaustion values.
12. Replace §9.5 with ADR-021's direction-specific keys, exact HMAC headers/canonicalization, unsigned trace propagation, 256 KiB limit, 300-second window, unauthenticated/platform evidence rule, authenticated unattributed evidence, Provider-owned identity resolution, tenant-owned receipt sequence, HTTP outcomes, and duplicate/same-ID-different-payload rules.
13. §9.6: define `SIMULATOR` as the only Release 0.2 Provider, specify Provider idempotency-record timing and replay, prohibit routing/failover/Merchant selection, limit deterministic scenarios to Provider processing and webhooks, keep settlement generation outside Release 0.2, and prohibit an unauthenticated administrative scenario API.
14. §§11.2–11.5: add ADR-021 trace propagation, bounded metric names/labels, two Grafana dashboards, and runbook-linked alerts.
15. §12.2: state that the existing Core project remains at repository root for Release 0.2 and `applications/provider-simulator` is the only new application subproject required by this release.
16. §§13.1–13.4: add every verification condition in this plan and classify event loss, Provider-evidence-free completion, duplicate financial effect, blind retry after ambiguity, and transaction-wrapped Provider calls as release blockers.
17. Replace §14.2 with the outcome, included scope, excluded scope, and exit gate in this plan.
18. §16: add ADR-021 only after acceptance and preserve ADR-004/005/006/009 provenance.
19. §§17.2–17.4 and Appendix C: add Release 0.2 correctness evidence and the Kafka, outbox, consumer lag, Provider outage, ambiguous outcome, webhook backlog, financial dead-letter, and stuck Payment runbooks.

#### Exact proposed Technical Specification text

The following wording is proposed verbatim after ADR-021 acceptance. The controlled revision may adjust pagination and table formatting, but not these semantics or values.

Document control and revision history:

```text
Version: 1.6
Status: Approved for Release 0.2 implementation - reconciled through ADR-021
Supersedes: LedgerOps Technical Design and Architecture Specification v1.5
Related product document: LedgerOps Product Definition Document v1.6
Revision: 21 July 2026 - Defined Release 0.2 Payment Attempt, messaging, Provider evidence, retry, webhook, Simulator, and observability semantics through ADR-021 - Approved.
```

Add to §4.3 Module ownership:

```text
Messaging owns generic outbox, inbox, dead-letter, claim, and publisher-lease records. Messaging knows no Payment, Provider, Ledger, Risk, or Reversal business semantics. Business modules append messages only through messaging::api inside their existing PostgreSQL transactions.

Payment owns immutable Payment Attempt business records for Payments in Release 0.2, Payment-to-attempt relationships, Payment lifecycle, Payment lifecycle persistence, Payment lifecycle events, and one immutable accepted-final-result application record per Payment. Reversal attempts remain Release 0.3.

Provider owns provider adapters, durable Provider work and recovery state, immutable interaction and result evidence, webhook receipts, retry scheduling, ambiguity, and status recovery. Provider never mutates Payment or Ledger. Payment verifies Provider evidence only through provider::api.

The allowed dependency direction is Provider -> messaging::api, Payment -> provider::api, and Payment -> messaging::api. Provider must not depend on payment::api. Provider resolves tenant, Payment, and Payment Attempt identity from mappings persisted from SubmitPaymentToProvider and never queries Payment.
```

Add to §5.1 Primary aggregates:

```text
A Release 0.2 Payment Attempt is an immutable Payment-owned record containing attemptId, tenantId, paymentId, positive sequence, providerId, providerIdempotencyKey, initiatedAt, immutable request-intent fields, and requestIntentHash. Release 0.2 supports exactly providerId = SIMULATOR; every initial attempt and automatic retry uses it. There is no Provider router, failover, Merchant-selectable Provider configuration, or multi-Provider policy. Sequence starts at 1 and is unique by (tenant_id, payment_id, sequence). Every attempt for one Payment uses payment:<lowercase canonical Payment UUID> as the Provider idempotency key. The Payment aggregate does not embed an attempt collection.

requestIntentHash is SHA-256 over canonical JSON containing exactly providerId, merchantId, customerId, normalized amount, currency, and paymentMethodCategory in that property order. The object uses UTF-8 JSON encoding. Normalized amount is the Payment Money amount after currency-defined precision validation, serialized as a JSON decimal string using BigDecimal.toPlainString(). Contract fixtures fix escaping and byte-for-byte serialization. No other field contributes to the hash.

Payment also owns one immutable accepted-final-result application record per Payment. It identifies tenantId, paymentId, attemptId, providerEvidenceId, providerResultId, final category, provider reference when present, and appliedAt. It does not replace Provider evidence or add a Payment status.
```

Append to §5.6 Atomic Payment completion:

```text
In Release 0.2, payment-provider-result-consumer-v1 invokes the existing CompletePaymentAfterProviderSuccess use case only after provider::api verifies matching durable Provider evidence. The Payment result transaction commits the inbox record, accepted-final-result evidence, exact ADR-020 Payment/Ledger effect, and PaymentCompleted outbox record once. The completion use case joins this transaction. It is not rewritten, duplicated, exposed publicly, or executed with REQUIRES_NEW.
```

Replace §§7.5–7.7 with:

```text
Business state and its outbox record commit in one PostgreSQL transaction. Outbox states are PENDING, CLAIMED, RETRYABLE, PUBLISHED, and DEAD. Messaging enforces UNIQUE (producer_name, deduplication_key). Release 0.2 producer names are exactly payment and provider, exposed through a typed ProducerName or equivalent closed value rather than an unrestricted String.

Exact mappings are:

producerName = payment; deduplicationKey = payment-submission:<attemptId>; messageType = SubmitPaymentToProvider
producerName = payment; deduplicationKey = payment-final:<paymentId>; messageType = PaymentCompleted or PaymentFailed
producerName = provider; deduplicationKey = provider-result:<tenantId>:<providerId>:<providerResultId>; messageType = ProviderResultObserved
producerName = provider; deduplicationKey = payment-retry:<retryRequestId>; messageType = PaymentSubmissionRetryRequested

PaymentCompleted and PaymentFailed share the payment-final identity; changed terminal content is a typed consistency error.

The deduplication content hash is SHA-256 over canonical JSON containing exactly messageType, schemaVersion, aggregateId, tenantId, topic, partitionKey, and payload in that property order. It uses UTF-8 JSON encoding. The payload schema and contract fixtures fix escaping, number representation, nested-property ordering, and byte-for-byte serialization. The hash excludes messageId, occurredAt, correlationId, causationId, traceparent, tracestate, delivery state, leases, and retry metadata. Equivalent business content returns the existing record and original messageId. Different canonical business content under the same producerName and deduplicationKey raises a typed consistency error.

Outbox and Provider work records contain leaseOwner, leaseToken, and leaseExpiresAt. Claim candidates are due PENDING, due RETRYABLE, and CLAIMED records whose lease has expired. Each claim assigns a new leaseToken. A publisher claims with FOR UPDATE SKIP LOCKED in a short transaction, commits, publishes outside a database transaction, and marks the result later only with the current token. Renewal and every mutable completion or update require that token. A stale publisher cannot mark PUBLISHED, RETRYABLE, or DEAD. A stale Provider worker cannot mark COMPLETED, WAITING_STATUS, WAITING_RETRY_REQUEST, or UNRESOLVED. A stale worker might already have produced an external effect, so duplicate handling remains mandatory.

The lease is 30 seconds and may renew every 10 seconds with the current token. Publication retries at most 10 times using one-second exponential backoff capped at 60 seconds with deterministic plus-or-minus 20-percent jitter. Attempt 10 failure becomes DEAD. Kafka acknowledgement followed by a crash before database marking may duplicate publication; LedgerOps makes no end-to-end exactly-once claim.

For a valid envelope with a valid messageId, inbox, consumer-failure, and consumer dead-letter identity are consumerName + messageId, and inbox status is exactly PROCESSED or DEAD. The inbox record and consumer business effect commit in one transaction. A duplicate acknowledges without repeating the effect. A failed business transaction rolls back the inbox insertion. After rollback, a separate short error-handler transaction increments failureCount, firstFailedAt, lastFailedAt, and bounded lastReason. On failure five, one transaction records inbox status DEAD and the consumer dead letter; acknowledgement occurs only after commit. Unsupported schema versions with a valid envelope and messageId use that terminal transaction immediately. A malformed payload uses bounded failure counting unless classified permanently invalid, when it may dead-letter immediately. Successful processing records PROCESSED.

If an envelope cannot be parsed sufficiently to obtain a valid messageId, Messaging creates no inbox or normal consumer-failure record. It immediately writes one transport dead letter unique by (consumer_name, topic, partition, offset), preserving the raw-record hash, bounded safe bytes or summary, topic, partition, offset, consumer name, reason, and correlation data when safely available. Acknowledgement occurs only after that transaction commits. Release 0.2 provides no public manual replay endpoint.

Outbox publication dead letters are unique by outboxId. Consumer dead letters are unique by consumerName + messageId. Transport dead letters are unique by consumerName + topic + partition + offset. Release 0.2 automatically purges none of the outbox, inbox, dead-letter, Provider evidence, Payment Attempt, accepted-result, or webhook receipt records.
```

Replace §9.2 Submission workflow with:

```text
Payment atomically locks an APPROVED Payment, creates sequence-1 Payment Attempt evidence, applies Payment.startProcessing(), updates the expected version, and inserts SubmitPaymentToProvider into the outbox. Failure rolls back the attempt, transition, and command.

The Provider command consumer transaction inserts consumer inbox evidence and creates or locates durable Provider work, then commits. A leased worker calls the Provider Simulator outside every database transaction. A later Provider transaction appends immutable interaction/result evidence and ProviderResultObserved to the outbox.

The Payment result consumer transaction inserts its inbox record, verifies matching durable Provider evidence through provider::api, validates the attempt and Payment, applies the exact result matrix, records an accepted final result for a terminal transition, and inserts PaymentCompleted or PaymentFailed into the outbox. Kafka publication occurs later and may repeat.

Provider owns RetryDisposition with exactly SAFE_TO_RESUBMIT, STATUS_RECOVERY_REQUIRED, and NOT_RETRYABLE. ProviderResultObserved carries the disposition. SUCCESS, DECLINED, and definitive PERMANENT_FAILURE are NOT_RETRYABLE. ACCEPTED, PENDING, and UNKNOWN are STATUS_RECOVERY_REQUIRED. TEMPORARY_FAILURE is SAFE_TO_RESUBMIT only when durable evidence proves that no Provider transaction was accepted or created; otherwise it is STATUS_RECOVERY_REQUIRED. A status query that returns an existing Provider transaction prohibits resubmission.

Provider work distinguishes SUBMISSION and STATUS_QUERY. Only STATUS_QUERY work may move from WAITING_STATUS to RETRYABLE and perform another Provider HTTP call. SAFE_TO_RESUBMIT evidence moves SUBMISSION work to WAITING_RETRY_REQUEST. When due, that work is claimed directly with a new leaseToken for retry-request creation; it never becomes Provider-call RETRYABLE and never repeats the Provider call for its Payment Attempt.

When WAITING_RETRY_REQUEST becomes due, the current fenced worker verifies leaseToken, creates or finds one immutable Provider retry-request record, inserts PaymentSubmissionRetryRequested into the outbox, marks the original work COMPLETED, and commits once. The record contains retryRequestId, tenantId, paymentId, previousAttemptId, providerEvidenceId, providerId, and requestedAt. PostgreSQL enforces UNIQUE (tenant_id, provider_evidence_id). retryRequestId is generated once and persisted. Repeated scheduling or crash recovery returns the same retryRequestId and outbox record. The outbox uses producer provider and payment-retry:<retryRequestId>.

Payment consumes the command in an inbox-backed transaction, verifies the authoritative evidence and disposition through provider::api, locks a PROCESSING Payment, creates the next immutable SIMULATOR Payment Attempt, persists a retry-application record unique by (tenant_id, retry_request_id), and inserts SubmitPaymentToProvider. That command creates new SUBMISSION work for the new attempt. Repeated retryRequestId returns the existing attempt and command. Provider never depends on payment::api.

SUCCESS invokes the existing CompletePaymentAfterProviderSuccess boundary. DECLINED and contract-valid definitive PERMANENT_FAILURE may apply Payment.fail() while PROCESSING. ACCEPTED, PENDING, TEMPORARY_FAILURE, and UNKNOWN leave Payment PROCESSING. UNKNOWN enters status recovery and never causes blind resubmission. Exact final replay returns the existing result; a conflicting final result becomes durable operational or dead-letter evidence and never overwrites the accepted result.
```

Replace §9.4 Resilience policies with:

```text
Release 0.2 permits at most three automatic Payment Attempts: one initial attempt and two intentional safe retries. A low-level connection retry may run once within the same attempt only when the client proves no request bytes were sent; its delay is 200 ms with deterministic plus-or-minus 20-percent jitter. Every potentially transmitted timeout becomes UNKNOWN and enters status recovery.

Intentional retry delay starts at 5 seconds, then 10 seconds, is capped at 60 seconds, and uses deterministic plus-or-minus 20-percent jitter. Status recovery permits 12 queries; delay starts at 10 seconds, doubles to a 5-minute cap, and uses the same jitter. Connection timeout is 1 second, read timeout is 3 seconds, and total timeout is 5 seconds. Recovery exhaustion leaves Payment PROCESSING and marks Provider work UNRESOLVED.

The circuit breaker uses a 20-call count window, 10 minimum calls, 50-percent failure and slow-call thresholds, a 3-second slow-call threshold, a 30-second open duration, and 3 half-open calls. The Provider bulkhead permits 10 concurrent calls per Core instance with zero wait. An open circuit reschedules durable work without consuming an attempt or query count because no call occurred.
```

Replace §9.5 Webhook reception and processing with:

```text
Core accepts Provider webhooks only at POST /internal/provider/v1/webhooks. The request body limit is 256 KiB. Required headers are Content-Type, X-LedgerOps-Key-Id, X-LedgerOps-Timestamp, X-LedgerOps-Event-Id, and X-LedgerOps-Signature. The timestamp is valid when its absolute difference from the injected Clock is no greater than 300 seconds.

Core -> Provider Simulator and Provider Simulator -> Core use separate secret sets. Each key ID maps to exactly one provider, provider-client identity, and direction. Unknown and wrong-direction keys return 401. HMAC-SHA256 signs this UTF-8 sequence with ASCII LF separators and no trailing LF:

v1
<UPPERCASE_HTTP_METHOD>
<RAW_PATH_WITHOUT_QUERY>
<KEY_ID>
<TIMESTAMP_DECIMAL>
<REQUEST_ID_OR_EVENT_ID>
<LOWERCASE_SHA256_RAW_BODY>

The signature is v1=<base64url-without-padding MAC> and comparison is constant time. traceparent and tracestate propagate separately and are not signed.

Reception hashes the raw body before its final response. Unknown keys, wrong-direction keys, invalid signatures, and invalid timestamps persist bounded platform security rejection evidence only, create no Provider receipt or work, and return 401. A valid signature with malformed JSON persists Provider-scoped unattributed invalid evidence, creates no tenant-owned receipt or work, and returns 400. A valid signature and valid payload without a Provider-owned mapping persists a durable unattributed Provider receipt, emits an operational signal, creates no tenant-owned work, and returns 202.

Only a successful Provider-owned mapping can create a tenant-owned receipt and work. Tenant, Payment, and attempt identity come from mappings persisted from SubmitPaymentToProvider and are never trusted from webhook body fields. Canonical event identity is (tenant_id, provider_id, provider_event_id). A new mapped event stores one canonical event and work item, then returns 202. The same identity and payload hash stores duplicate evidence, creates no work, and returns 202. The same identity with a different hash stores conflict evidence, creates no work, and returns 409. Oversized input returns 413 and stores only bounded rejection metadata when available.

Webhook processing is asynchronous Provider-owned work. It preserves duplicate, invalid, out-of-order, and conflicting evidence and reaches Payment only through the same ProviderResultObserved Stage A and inbox-backed Stage B flow. It never regresses terminal Payment state or creates a duplicate Ledger effect.
```

Add to §9.6 Provider Simulator:

```text
Release 0.2 supports exactly one Provider: providerId = SIMULATOR. Every initial Payment Attempt and automatic retry uses SIMULATOR. Release 0.2 has no Provider router, Provider failover, Merchant-selectable Provider configuration, or multi-Provider policy. Adding those capabilities requires a later approved decision.

The Provider Simulator creates its Provider idempotency record when it accepts a request into its durable Provider transaction. Once that record exists, repeating the same provider idempotency key returns the original Provider transaction and cannot create another Provider-side transaction. A status query that returns an existing Provider transaction prohibits resubmission.
```

Replace §14.2 Release 0.2 with:

```text
Release 0.2 - Distributed Processing

Kafka at-least-once delivery, transactional outbox, consumer inbox, JSON/JSON Schema contracts, Payment-associated immutable Payment Attempts, Provider-owned durable work/evidence/recovery, a separate Provider Simulator and database, HMAC requests/webhooks, bounded Resilience4j policies, dead-letter handling, OpenTelemetry propagation, Prometheus metrics, initial Grafana dashboards, and release-appropriate PostgreSQL/Kafka/Provider verification.

Release 0.2 preserves the existing ADR-020 Payment-owned completion and Ledger replay boundary. It excludes Keycloak and authorization, Reversal, settlement/reconciliation, casework/corrections, polished UI, public manual replay, Redis without an approved need, Kubernetes, Terraform, AWS deployment, and applied AI.

Exit: committed commands survive outages; duplicate commands/results/webhooks produce one business and financial effect; Provider calls occur outside database transactions; definitive SUCCESS reaches ADR-020; UNKNOWN recovers without blind resubmission; retry/recovery limits are enforced; unresolved and dead work is durable and visible; and traces, metrics, contracts, and automated crash/recovery tests pass.
```

### Product Definition amendments

None. Product Definition v1.6 already defines the required Provider behavior, immutable attempts/evidence, duplicate safety, timeout ambiguity, recovery, and operational outcomes. Release 0.2 implements a bounded subset without changing a MUST requirement or lifecycle.

### Retrospective ADR records

Slice 0 created these files:

- `docs/adr/ADR-004-use-transactional-outbox.md`
- `docs/adr/ADR-005-use-at-least-once-messaging-with-idempotent-consumers.md`
- `docs/adr/ADR-006-use-business-capability-kafka-topics.md`
- `docs/adr/ADR-009-run-provider-simulator-as-a-separate-application.md`

Each file must state: “This file is a retrospective record of a decision originally approved in LedgerOps Technical Specification v1.0 on 13 July 2026. It is not a recovered original and introduces no new decision.” Each record must reproduce only the corresponding decision already recorded by the Technical Specification: transactional outbox; at-least-once delivery with idempotent consumers; business-capability Kafka topics with aggregate partition keys; or a separate Provider Simulator application and database.

### Slice 0 README synchronization record

At Slice 0 completion, the repository status became:

> Release 0.2 — Distributed Processing is active under accepted ADR-021 and the active Release 0.2 plan. It adds durable Provider delivery around the completed ADR-020 Payment-to-Ledger boundary. Release 0.2 does not add Keycloak, Reversal, reconciliation, public replay controls, Kubernetes, or cloud deployment.

Update the repository tree only when `applications/provider-simulator`, event contracts, runbooks, and observability assets exist. Do not present a planned component as implemented.

### Slice 0 AGENTS.md synchronization record

At Slice 0 completion, the current-scope opening became:

> The active milestone is **Release 0.2 — Distributed Processing**. Follow accepted ADR-021 and `docs/plans/release-0.2-distributed-processing.md`. Preserve the existing ADR-020 completion boundary. Release 0.2 permits Kafka, transactional outbox/inbox, Payment Attempts for Payments, Provider processing and recovery, the separate Provider Simulator, Resilience4j, OpenTelemetry, Prometheus, and initial Grafana dashboards. It still excludes Keycloak, authorization, Reversal, settlement/reconciliation, casework/corrections, public manual replay, Redis without an approved need, Kubernetes, Terraform, AWS deployment, and applied AI.

Add non-negotiable rules for no external call inside a database transaction, no completion without durable matching Provider evidence, exact inbox identity, stable Provider idempotency key, bounded retries, and no blind resubmission after `UNKNOWN`.

### Traceability synchronization

Slice 0 renames the matrix scope to include Release 0.2 and adds `Planned` evidence rows for PAY-03, PAY-04, PRV-01 through PRV-05, DEV-01 through DEV-04, BR-01, BR-05, BR-06, BR-12, and BR-13. It adds technical-decision rows for:

- ADR-004/005/006 and Technical §§7.1–7.8: outbox, inbox, at-least-once, topics, partition key, and JSON Schema;
- ADR-009 and Technical §§3, 9, and 12.2: separate Provider Simulator and database;
- ADR-021 and Technical §§4–7, 9, 11, 13, 14.2, and 17: exact Release 0.2 semantics; and
- ADR-020 preservation through the Stage B SUCCESS path.

Change each evidence row from `Planned` only when its slice has executable evidence. At the Release 0.2 exit, report `PAY-03`, `PAY-04`, `PRV-01` through `PRV-05`, `DEV-01`, `DEV-03`, `DEV-04`, `BR-01`, `BR-12`, and `BR-13` as `Partial`. Report `DEV-02` as `Deferred` or `Planned`; Provider Simulator-to-Core webhooks do not implement DEV-02 merchant webhook testing. Report `BR-05` and `BR-06` as `Implemented` only after all required executable evidence passes.

Use these exact initial rows after ADR-021 acceptance:

```text
| PAY-03 | Release 0.2 implements the automated Provider lifecycle path while preserving the approved Payment state machine and ADR-020 completion. | Planned against ADR-021 Slices 1, 4, and 5. | Planned; Release exit Partial |
| PAY-04 | Release 0.2 persists Payment Attempt and Provider evidence for future composed detail views but does not deliver the complete product-facing view. | Planned against ADR-021 Slices 1, 3, and 7. | Planned; Release exit Partial |
| PRV-01 | Release 0.2 provides deterministic Provider Simulator scenarios without the later authenticated administration workflow. | Planned against ADR-021 Slices 3 and 7. | Planned; Release exit Partial |
| PRV-02 | Release 0.2 implements immutable Payment-associated attempts and separate Provider-owned interaction/result evidence. Reversal attempts remain Release 0.3. | Planned against ADR-021 Slices 1, 3, and 5. | Planned; Release exit Partial |
| PRV-03 | Release 0.2 durably receives and asynchronously processes signed Provider webhooks while preserving duplicate, invalid, out-of-order, and conflicting evidence without duplicate effects. | Planned against ADR-021 Slice 6. Product timeline UI remains later. | Planned; Release exit Partial |
| PRV-04 | Release 0.2 implements bounded automatic safe retry and status recovery. Public manual retry remains deferred until Release 0.3 authorization and audit. | Planned against ADR-021 Slice 5. | Planned; Release exit Partial |
| PRV-05 | Release 0.2 exposes Provider health through bounded Prometheus metrics and initial Grafana dashboards. | Planned against ADR-021 Slice 7. | Planned; Release exit Partial |
| DEV-01 | Release 0.2 adds signed Provider and event-contract developer reference material. | Planned across Slices 1–7. | Planned; Release exit Partial |
| DEV-02 | Merchant webhook testing is not implemented by Provider Simulator-to-Core webhooks. | Outside Release 0.2 implementation scope. | Deferred or Planned |
| DEV-03 | Release 0.2 adds deterministic distributed-processing failure stories without an authenticated scenario launcher. | Planned against ADR-021 Slices 3 and 7. | Planned; Release exit Partial |
| DEV-04 | Release 0.2 adds synthetic Provider, attempt, messaging, and failure evidence to the demo. | Planned against ADR-021 Slice 7. | Planned; Release exit Partial |
| BR-01 | Release 0.2 enforces tenant ownership for its mapped Core business records and isolates explicitly unattributed webhook-security evidence from business effects. | Planned migration, constraint, mapping, and tenant-isolation evidence. | Planned; Release exit Partial |
| BR-05 | Duplicate commands, Provider results, status observations, and webhooks must produce one accepted business and financial effect. | Planned inbox, business-outbox hash, fenced-lease, Provider-result identity, accepted-final-result, terminal-state, and ADR-020 replay evidence. | Implemented only after all required executable evidence passes |
| BR-06 | Automatic submission retry and status recovery use the exact ADR-021 disposition and attempt limits; ambiguity or exhaustion never invents a final failure. | Planned Clock/scheduler, RetryDisposition, no-acceptance proof, attempt-count, UNKNOWN, recovery, and unresolved-evidence tests. | Implemented only after all required executable evidence passes |
| BR-12 | Release 0.2 uses injected time for leases, retries, signatures, evidence, and deterministic tests. | Planned across Slices 1–7. | Planned; Release exit Partial |
| BR-13 | Release 0.2 keeps Provider progress separate from Payment, Reversal, and Reconciliation status. | Planned module, lifecycle, and persistence-boundary evidence. | Planned; Release exit Partial |
```

### Implemented diagrams

The [Release 0.2 Provider-flow diagrams](../architecture/diagrams/release-0.2-provider-flow.md) provide:

- a module ownership diagram showing Payment, Messaging, Provider, and Ledger APIs and owned schemas;
- a transaction/network sequence for initial submission, command consumption, Provider call, Stage A, and Stage B;
- an outbox/inbox crash-window diagram;
- a webhook reception/processing sequence; and
- a deployment diagram showing root Core, Kafka, Core PostgreSQL, Provider Simulator, and Simulator PostgreSQL with no shared database access.

Each diagram includes equivalent explanatory text and uses the accepted ADR-021 names.

### Slice 0 proposed-runbook record

Slice 0 planned read-only diagnosis and recovery guidance for:

- Kafka unavailable;
- outbox backlog;
- consumer lag;
- financial dead-letter message;
- Provider outage;
- ambiguous Payment outcome;
- webhook backlog;
- claim-lease recovery; and
- stuck `PROCESSING` Payments.

The implemented [Release 0.2 operations runbook](../runbooks/release-0.2-operations.md) provides this guidance without an unauthenticated mutation or replay command. It includes safe read-only diagnosis and the controlled local-demo reset path.

### Slice 0 proposed-contract and demo record

Slice 0 planned versioned JSON Schemas and fixtures for the six ADR-021 contracts, Provider HTTP schemas and HMAC fixtures, executable local-topology instructions, synthetic evidence, distributed failure stories, and a separate Release 0.2 demo. Slices 1–7 implemented those assets under `packages/event-contracts/v1`, `packages/provider-contracts/v1`, [Provider Simulator HTTP contract v1](../api/provider-simulator-v1.md), and the [Release 0.2 demo](../demo/release-0.2.md). The Release 0.1 demo remains historical evidence.

## Completion report

- Changed: Slices 0 through 7 are complete; ADR-021 remains accepted; the distributed Provider and messaging runtime, contracts, observability, operations evidence, and supporting documentation are implemented.
- Verified: Atomic submission, at-least-once delivery, duplicate safety, Provider execution, evidence-gated final-result application, retry and ambiguity recovery, signed webhooks, dead-letter behavior, trace propagation, bounded metrics, dashboards, migrations, architecture boundaries, and the full Release 0.2 gate pass.
- Incomplete: Product requirements intentionally marked `Partial` or `Deferred or Planned`; Release 0.3 identity, authorization, Reversal, reconciliation, corrections, and operational UI work have not started.
- Deviations: None from Product Definition v1.6, Technical Specification v1.6, ADR-016, ADR-020, or ADR-021.
