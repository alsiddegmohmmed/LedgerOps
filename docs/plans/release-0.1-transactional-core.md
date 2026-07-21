# Release 0.1 plan: Transactional Core

Status: Completed
Last updated: 2026-07-21
Authority: LedgerOps Product Definition v1.6 and LedgerOps Technical Design and Architecture Specification v1.5 only

This plan derives its scope and sequence from those two fixed documents. It does not amend, replace, or reinterpret product requirements, module ownership rules, correctness rules, or release boundaries.

## Exit outcome

Release 0.1 ends when concurrent equivalent requests create one logical payment, conflicting reuse of an idempotency key is rejected, and every completed payment has exactly one balanced immutable ledger posting committed atomically with payment completion.

This release remains a Spring Boot modular monolith backed by PostgreSQL. Kafka, outbox/inbox processing, Redis, Keycloak, Kubernetes, reconciliation, a polished frontend, and AI are explicitly deferred.

Release 0.1 includes:

- Spring Boot modular structure, PostgreSQL, Flyway, and tenant, merchant, and customer basics
- payment creation, idempotency, the payment state machine, and synchronous risk rules
- strict double-entry ledger and atomic payment completion
- OpenAPI, structured logs, Testcontainers, domain tests, and integration tests

Release 0.1 may provide foundations for a broader product requirement without claiming that the requirement is fully implemented. In particular, TEN-01 is not complete in Release 0.1: the Product Definition requires a designated initial Merchant Admin and authorised access, while the Technical Specification assigns application users, membership references, and role assignments to the Identity module. Release 0.3 introduces Keycloak, identity, tenant membership, permissions, merchant scope, authorization, and tenant-isolation enforcement. Release 0.1 must not place identity-owned data in the Tenancy module or simulate authorization.

## Current implementation evidence

### Application and tenancy foundation

- Gradle/Spring Boot Java 21 application foundation
- Spring Modulith verification test and `tenancy` module declaration
- `Tenant`, `TenantId`, and lifecycle rules as plain domain Java
- Flyway-created `tenancy.tenants` table with critical checks and optimistic version column
- explicit domain/JPA mapping through a persistence adapter
- PostgreSQL Testcontainers schema and persistence tests
- transactional tenant application service for create, read, activate, suspend, and archive
- typed application failures for missing tenants, duplicate names, and invalid lifecycle transitions
- application and PostgreSQL tests proving lifecycle persistence, duplicate-name rejection, unchanged state after invalid transitions, and readable suspended history
- validated tenant HTTP endpoints with correlated RFC 7807 problem responses
- Release 0.1 OpenAPI contract with tenant operations and error schemas

### Merchant and customer foundations

- explicit `merchant` Modulith boundary depending only on the published tenancy API
- tenant-owned `Merchant` and `MerchantId` domain foundation with explicit merchant state
- Flyway-owned `merchant.merchants` table with mandatory tenant ownership, tenant-scoped name uniqueness, optimistic versioning, and database checks
- tenant-scoped merchant repository adapter and PostgreSQL tests preventing cross-tenant reads and tenant reassignment
- explicit `customer` Modulith boundary depending only on the published Merchant API
- data-minimized Customer domain foundation with merchant-scoped customer reference and status
- Flyway-owned `customer.customers` table with mandatory tenant/merchant ownership, scoped reference uniqueness, optimistic versioning, and no personal or payment-secret columns
- tenant-and-merchant-scoped customer repository with PostgreSQL tests preventing cross-context reads and ownership reassignment

### Payment foundation

- explicit Payment module foundation with `Money` using `BigDecimal`, explicit currency, currency-defined precision, non-negative values, and same-currency arithmetic only
- immutable `Payment` aggregate with stable identity, tenant/merchant ownership, customer identifier, positive amount, payment-method category, idempotency key, and initial `CREATED` status
- exact ADR-016 `PaymentStatus` vocabulary and transition rules, with exhaustive tests for every exposed source/target pair and terminal-state enforcement

### Payment creation and idempotency

- Flyway-owned `payment.payments` table with positive-amount, currency, exact-status, fingerprint, ownership, and tenant-wide idempotency constraints
- transactional Payment creation service using published tenant, merchant, and customer activity checks instead of cross-module table access
- PostgreSQL `ON CONFLICT` arbitration on `(tenant_id, idempotency_key)` so concurrent equivalent requests return one logical Payment
- SHA-256 request fingerprint covering merchant, customer, normalized amount, currency, and payment-method category
- explicit conflicts for changed content, including a different merchant under the same tenant and key
- validated HTTP contract with `201` creation, `200` equivalent replay, RFC 7807 validation/reference/idempotency failures, correlated request IDs, and structured lifecycle logs
- sequential, cross-tenant, cross-merchant, suspended-tenant, database-constraint, coordinated-concurrency, and HTTP integration tests using PostgreSQL Testcontainers

**Implementation limit:** This evidence is a foundation, not a complete TEN-01 implementation. Payment creation now blocks new activity for inactive tenants through a published Tenancy check. Merchant and Customer application write use cases must apply the same rule when those use cases are introduced. Identity, membership, authorization, and the designated initial Merchant Admin remain Release 0.3 evidence under the Technical Specification.

## Ordered implementation slices

| Order | Slice | Main evidence | Status |
|---:|---|---|---|
| 0 | Codex operating documents and repository workflow | Agent rules, templates, traceability, clean build | Completed |
| 1 | Tenant lifecycle foundation | application use cases, API/OpenAPI contract, correlated RFC 7807 failures, and PostgreSQL integration tests; TEN-01 remains Partial | Completed |
| 2 | Merchant foundation | tenant-owned aggregate, schema, tenant-scoped repository, module-boundary and isolation tests | Completed |
| 3 | Customer foundation | data-minimized aggregate, tenant/merchant-owned schema, scoped repository, module-boundary and isolation tests | Completed |
| 4 | Money and payment domain | Money and required value objects, immutable Payment aggregate, exact state machine, invariant and exhaustive transition tests | Completed |
| 5 | Payment creation and idempotency | complete PAY-01 request fields; unique `(tenant_id, idempotency_key)` constraint; merchant-aware request fingerprint; repeat, conflict, and concurrent PostgreSQL tests | Completed |
| 6 | Synchronous risk rules | evaluated and triggered rules, score contributions, bounded thresholds, approve/reject/review evidence | Completed |
| 7 | Double-entry ledger | tenant-scoped accounts, balanced immutable postings, database constraints, property/integration tests | Completed |
| 8 | Atomic payment completion | accepted ADR-020 exact two-entry template, full replay validation, one joined PostgreSQL transaction, forced-failure rollback, and concurrency tests | Completed |
| 9 | Release API and evidence hardening | OpenAPI, structured logs, architecture tests, README/demo instructions, traceability audit | Completed |

Only one implementation slice should change shared domain contracts or schemas at a time. Reviews of architecture, persistence/concurrency, or tests may run independently after a stable diff exists.

## Slice 1 — tenant lifecycle foundation

Outcome: expose the already-implemented tenant lifecycle through an application boundary without weakening the domain/persistence separation or claiming full TEN-01 completion.

Scope boundary: this slice implements tenant creation and lifecycle mechanics only. It does not add an initial-admin field to the Tenant aggregate, create identity or membership records, or present an unauthenticated endpoint as secure. Those responsibilities remain owned by the Identity module and are completed with Release 0.3 identity and authorization work.

Completed work:

1. Defined tenant application use cases and typed outcomes for create, activate, suspend, archive, and read.
2. Added an HTTP contract with validation and correlated RFC 7807 problems. The contract documents that authentication and role authorization are not Release 0.1 evidence.
3. Added PostgreSQL-backed tests for lifecycle persistence, duplicate names, lifecycle transition failures, and readable suspended history. Cross-module suspended-write enforcement is verified as merchant, customer, and payment write use cases are added.
4. Updated traceability, API documentation, and this plan with exact verification evidence.

## Slice 2 — merchant foundation

Outcome: establish the Merchant aggregate as a separately owned module and schema without inventing provider configuration, routing, limits, or lifecycle transitions that the authoritative documents do not yet define at field level.

Completed work:

1. Published a plain tenant reference through the Tenancy module API and restricted the Merchant module dependency to that named interface.
2. Added tenant-owned merchant identity, name, and state domain types with no Spring or persistence dependencies.
3. Added the Flyway-owned merchant schema and explicit domain/JPA mapping with mandatory `tenant_id`, tenant-scoped name uniqueness, database state checks, and optimistic versioning.
4. Proved tenant-scoped reads, same-name independence across tenants, mandatory ownership, immutable tenant assignment, migration installation, and Modulith boundaries with PostgreSQL-backed tests.

Provider configuration, routing settings, processing limits, merchant administration, and authorization are not claimed by this foundation.

## Slice 3 — customer foundation

Outcome: establish the Customer aggregate as a data-minimized, merchant-related module without storing real card credentials, government identifiers, unnecessary personal profiles, or speculative risk/token attributes.

Completed work:

1. Published an opaque tenant-and-merchant reference through the Merchant module API and restricted Customer to that single named-interface dependency.
2. Added customer identity, merchant-scoped customer reference, and status as plain domain types.
3. Added the Flyway-owned customer schema and explicit domain/JPA mapping with mandatory tenant and merchant ownership, scoped reference uniqueness, state checks, and optimistic versioning.
4. Proved tenant/merchant-scoped reads, reference independence across merchants, duplicate rejection within a merchant, immutable ownership, data-minimized columns, migration installation, and Modulith boundaries with PostgreSQL-backed tests.

Risk attributes and tokenized payment references remain Customer-owned future fields, but are not added until their behavior is required and defined.

## Slice 4 — Money and payment domain

Completed Money work:

1. Added a Payment-module `Money` value object backed only by `BigDecimal` and explicit `Currency`.
2. Enforced currency-defined decimal precision, prohibited negative values, and prohibited cross-currency arithmetic.
3. Added invariant tests for SAR, JPY, and KWD precision, zero/positive semantics, arithmetic, currency mismatch, and negative-result rejection.

Documentation reconciliation is complete through accepted ADR-016, ADR-017, ADR-018, ADR-019, and ADR-020. Product Definition v1.6 and the current Technical Specification v1.6 authorize the completed Slice 8 implementation without changing the accepted Release 0.1 boundary.

Completed Payment-domain work:

1. Added stable Payment and Customer identifiers, a validated idempotency key, and an open payment-method category value object without inventing unsupported category values.
2. Added an immutable Payment aggregate containing every PAY-01 domain field and deriving tenant ownership from the published merchant reference.
3. Enforced positive payment amounts and `CREATED` as the only creation state.
4. Implemented and exhaustively tested every approved transition, every rejected exposed transition, and all terminal states.

Payment implementation must follow this contract:

- `PaymentStatus`: `CREATED`, `VALIDATING`, `RISK_REVIEW`, `APPROVED`, `REJECTED`, `PROCESSING`, `COMPLETED`, `FAILED`, `REVERSED`.
- Payment transitions: `CREATED -> VALIDATING`; `VALIDATING -> APPROVED | RISK_REVIEW | REJECTED`; `RISK_REVIEW -> APPROVED | REJECTED`; `APPROVED -> PROCESSING`; `PROCESSING -> COMPLETED | FAILED`; `COMPLETED -> REVERSED`.
- `REJECTED`, `FAILED`, and `REVERSED` are terminal; Payment has no recovery transition from `FAILED`.
- Provider-attempt, Reversal, and Reconciliation progress remain separate from `PaymentStatus`.
- `COMPLETED` requires provider `SUCCESS` and exactly one corresponding balanced ledger transaction committed atomically; reconciliation completion is independent.
- Reversal remains a separate, full-only aggregate introduced in its scheduled release, not Release 0.1.

Risk records, provider attempts, ledger posting, and cross-aggregate completion/reversal transaction boundaries remain in their scheduled slices.

## Slice 5 — Payment creation and idempotency

Completed work:

1. Added the Payment-owned PostgreSQL schema and an atomic insert-or-find store using the exact tenant-wide `(tenant_id, idempotency_key)` boundary.
2. Added published read-only activity checks for tenant, merchant, and customer references, preserving module ownership while preventing new Payment activity for inactive or wrongly scoped records.
3. Added a transactional creation service that returns the original Payment for equivalent repeats and rejects materially different content, including a different merchant.
4. Added the Payment HTTP/OpenAPI contract, correlated RFC 7807 failures, structured logs, and PostgreSQL-backed sequential, concurrent, conflict, isolation, reference, and schema tests.

Risk evaluation, the standalone Ledger slice, Slice 8 Payment-success posting, and Slice 9 release evidence are complete under the approved Release 0.1 baseline.

## Slice 6 — synchronous deterministic Risk evaluation

Status: Completed. The deterministic Risk domain model, published `risk::api`, V5 PostgreSQL schema, Risk-owned JDBC persistence adapter, version-aware Payment lifecycle persistence, and both Payment-owned transaction services are implemented. Domain, application, PostgreSQL/Testcontainers, concurrency, rollback, and Modulith tests pass.

Outcome: evaluate every eligible Payment synchronously against the tenant's explicitly persisted active Risk profile, preserve reproducible append-only evidence, and move the Payment from `VALIDATING` through exactly one approved transition.

Approved implementation contract:

1. Support exactly `PAYMENT_AMOUNT_THRESHOLD`. A profile may contain multiple instances; an enabled rule is eligible only for the Payment currency and triggers when `paymentAmount >= amountThreshold`.
2. Persist versioned tenant profiles, profile-owned rules, one append-only initial evaluation per Payment, and every eligible rule result. Do not use a global fallback profile or `evaluation_kind`.
3. Sum triggered contributions into `uncappedScore`, cap `finalScore` at 100, and apply the exact thresholds `1 <= reviewThreshold < rejectThreshold <= 100` to produce `APPROVE`, `MANUAL_REVIEW`, or `REJECT`.
4. Let Payment orchestrate two short PostgreSQL transactions. Transaction 1 commits `CREATED -> VALIDATING`. Transaction 2 atomically persists the Risk evidence and applies `approve()`, `requestRiskReview()`, or `reject()` through optimistic compare-and-set.

Implementation boundary:

- Risk owns profiles, versions, rules, scoring, decisions, evaluations, and evaluated-rule results.
- Payment owns lifecycle persistence and cross-module orchestration and calls only the published `risk::api` interface.
- The creation-only Payment store remains separate; the focused version-aware lifecycle port handles orchestration updates.
- Do not add Risk behavior to `PaymentCreationService`, query another module's tables, create cross-schema foreign keys, or introduce pessimistic locking without demonstrated contention evidence.
- Missing or invalid configuration, evaluation failure, and concurrency failure leave Payment `VALIDATING` and preserve no partial Risk evidence.
- Merchant configuration UI/workflows, configuration audit, manual-review queues, human decisions, Kafka, and asynchronous Risk processing remain out of scope.

Required evidence before Slice 6 can be marked completed:

- threshold equality and amounts below, equal to, and above the rule threshold
- multiple triggered contributions, clean zero, uncapped score, and the 100-point cap
- exact approve, manual-review lower/upper, and reject boundaries
- invalid thresholds/contributions, missing or multiple profiles, and no eligible currency rule
- rollback after Risk persistence failure and Payment optimistic-concurrency conflict
- one evaluation and one transition under coordinated concurrency; repeat returns the existing outcome
- append-only evidence, PostgreSQL migration/constraints, Modulith boundaries, and prohibited cross-module access

Completed domain increment:

- Added the Risk module with exactly `PAYMENT_AMOUNT_THRESHOLD`, versioned profiles, typed configuration failures, deterministic evaluation, exact score aggregation and capping, approved decisions, and immutable evaluated-rule evidence.
- Added plain-Java tests for amount below/equal/above the threshold, clean zero, contribution aggregation, score cap, exact decision boundaries, invalid thresholds and contributions, rule/profile ownership, currency eligibility, inactive and cross-tenant profiles, and evidence immutability.
- No persistence, migration, published `risk::api`, Payment module dependency, or Payment orchestration was introduced in this increment.

Completed persistence increment:

- Added the V5 Risk schema for tenant-owned versioned profiles, `PAYMENT_AMOUNT_THRESHOLD` rules, evaluations, and evaluated-rule results without any cross-schema foreign key.
- Added database validation for profile thresholds, rule values, score capping, decisions, applied contributions, one active profile per tenant, and one evaluation per `(tenant_id, payment_id)`.
- Protected versioned profiles, rules, evaluations, and evaluated-rule results from destructive history changes while permitting profile deactivation.
- Added Risk-owned profile/evaluation persistence ports and a transactional JDBC adapter for profile provisioning, active-profile loading, append-only evaluation storage, and tenant-scoped repeat lookup.
- Added PostgreSQL schema and persistence tests for constraints, ownership, round trips, uniqueness, immutability, and rollback. The tests compile but cannot execute until Testcontainers can reach Docker.

Completed published-API increment:

- Added the named `risk::api` interface with neutral tenant, Payment, amount, and currency inputs and the approved profile, score, decision, and evaluation result fields.
- Published the exact Risk decisions and typed configuration/processing failures without exposing Risk domain or persistence types to callers.
- Added an application service that returns an existing evaluation before loading configuration, evaluates new requests with the injected `Clock`, and preserves typed configuration failures while translating unexpected failures to a typed processing error.
- Changed Risk persistence to atomically append the initial evaluation or return the concurrent winner through PostgreSQL `ON CONFLICT`, avoiding exception-driven replay and transaction rollback-only state.
- Added application tests for contract fields, fixed-time evaluation, sequential replay, simulated concurrent-winner resolution, typed configuration failures, and unexpected-failure cause preservation. The coordinated PostgreSQL concurrency test passes.

Completed Payment lifecycle-persistence prerequisite:

- Added a focused lifecycle persistence port separate from the creation-only Payment store. It loads a Payment with its persistence version inside tenant scope.
- Added a JDBC compare-and-set update guarded by `(tenant_id, payment_id, version)`. A successful transition updates only `status`, increments `version` once, and records `updated_at`; a stale or missing row returns `false` for typed handling by Payment orchestration.
- Reused the existing non-null Payment `version` column; no migration or lifecycle-state change was required.
- Added passing PostgreSQL tests for versioned loading, tenant isolation, immutable business fields, stale-writer rejection, and coordinated writers producing exactly one version increment.

Completed Transaction 1 increment:

- Added a dedicated Payment-owned transactional service for validation start; it is separate from `PaymentCreationService` and from the future Transaction 2 service.
- Transaction 1 loads the tenant-scoped Payment and persistence version, requires `CREATED`, applies the existing `startValidation()` domain method, and updates through compare-and-set before committing `VALIDATING` at the next version.
- Added typed not-found, invalid-state, and optimistic-concurrency failures. Invalid state and failed compare-and-set do not return a successful transition.
- Added passing plain application and PostgreSQL tests for the exact transition, every typed failure, committed state, invalid repeat behavior, and concurrent starts producing one transition.

Completed Transaction 2 implementation increment:

- Authorized Payment to depend on `risk::api` only. No Payment code imports Risk domain, application, infrastructure, or persistence types.
- Added a Payment-owned transactional service that loads a versioned `VALIDATING` Payment, calls Risk with only tenant ID, Payment ID, amount, and currency, and maps `APPROVE`, `MANUAL_REVIEW`, and `REJECT` to `approve()`, `requestRiskReview()`, and `reject()` respectively.
- Transaction 2 joins Risk evidence persistence and the Payment compare-and-set in one PostgreSQL transaction. Risk configuration/processing errors escape without a Payment update; a failed Payment compare-and-set raises the typed concurrency error and rolls back newly inserted Risk evidence.
- Added plain tests for every decision mapping, the neutral request boundary, invalid Payment state, typed Risk failure, and optimistic-concurrency failure.
- Added passing PostgreSQL tests for all three decisions, missing configuration, repeat behavior, Risk-evidence failure rollback, Payment compare-and-set rollback, and coordinated concurrent evaluation producing exactly one evaluation, one rule-result set, and one Payment transition.

Slice 6 completion evidence: `./gradlew test --console=plain` executed 141 tests successfully, and `./gradlew check --console=plain` passed on 2026-07-20.

## Slice 7 — strict double-entry Ledger

Status: Completed. The immutable journal and account domains, account/posting persistence, atomic validation, append-only enforcement, and tenant-scoped balance and statement queries have passing domain and PostgreSQL tests. Atomic Payment completion remains correctly isolated in Slice 8.

Implemented journal-domain increment:

- Added the explicit Ledger Modulith boundary and plain-Java identifiers, account/source references, `AccountCode`, entry direction, positive currency-aware `LedgerAmount`, immutable `LedgerEntry`, and immutable `LedgerTransaction`.
- A posted transaction requires at least two entries, at least one debit and one credit, one tenant, one currency, account/amount currency agreement, and exactly equal debit and credit totals.
- Every transaction records a tenant-scoped source category and identifier for Payment, Reversal, settlement adjustment, or authorised correction. A compensating transaction is a new posting with an explicit original-transaction reference and cannot compensate itself.
- Entries use positive amounts with explicit `DEBIT` or `CREDIT` direction; zero, negative, and over-precision amounts are rejected.
- Tests cover immutability, tenant and source ownership, currency precision, account-currency mismatch, entry-count/direction/balance failures, mixed-currency rejection, compensation references, and 500 deterministically generated balanced posting shapes.

Completed account-domain increment:

- Replaced the open string account code with exactly `CUSTOMER_RECEIVABLE`, `MERCHANT_PAYABLE`, `PROVIDER_CLEARING`, `PLATFORM_FEE_REVENUE`, `REVERSAL_PAYABLE`, and `SETTLEMENT_RECEIVABLE`.
- Added `LedgerAccountStatus` with exactly `ACTIVE` and no lifecycle methods.
- Added an immutable `LedgerAccount` containing `accountId`, `tenantId`, `accountCode`, `currency`, `status`, and `createdAt`; creation always assigns `ACTIVE`.
- Added plain-Java tests for exact status/code vocabularies, valid catalog parsing, ACTIVE-only creation, immutable field exposure, and mandatory account identity, ownership, currency, and creation time.
- Updated journal-domain tests to use only approved account codes; no placeholder account codes remain.

Completed account-persistence increment:

- Added the Ledger-owned V6 PostgreSQL account schema and an explicit JDBC mapping behind a create-only repository port.
- Enforced unique `(tenant_id, account_code, currency)`, exact code/status checks, required ownership and currency format, immutable account records, and non-deletion.
- Added tenant-scoped lookup by account ID and deterministic account identity. No lookup discloses another tenant's account.
- Added PostgreSQL/Testcontainers tests for the exact schema and catalog, uniqueness boundaries, immutable storage, tenant isolation, independent tenant/currency identities, and adapter round trips.

Completed posting-persistence increment:

- Added the Ledger-owned V7 transaction and entry tables with tenant-aware, same-schema foreign keys to accounts and transactions.
- Added a short transactional posting service that validates every account through the tenant-scoped repository before persisting a transaction or any entry.
- Missing, cross-tenant, currency-mismatched, or account-code-mismatched references return typed validation failures and persist no posting data. ACTIVE-only eligibility remains explicit and is protected by the V6 account constraint.
- Added unique `(tenant_id, source_type, source_id)` enforcement so one financial source cannot create duplicate postings.
- Added append-only transaction/entry triggers and a deferred PostgreSQL verification that compares the persisted entry count, debit/credit presence, and entry totals with the balanced transaction header before commit.
- PostgreSQL/Testcontainers tests prove atomic success, account relationships, validation rollback, duplicate-source rejection, database-level balance rejection, compensation-reference integrity, append-only history, and Ledger-only foreign keys.

Completed account-query increment:

- Added tenant-scoped balance and statement queries derived from immutable Ledger entries. `LedgerAccount` still contains no entry or transaction collection.
- Balance and statement results expose separate debit and credit totals because the authoritative baseline does not define normal-balance presentation by account code.
- Statements use explicit `[fromInclusive, toExclusive)` boundaries, a maximum page size of 100, stable posting/transaction/entry ordering, full-period totals, and immutable result lists.
- A single PostgreSQL statement returns each page and its full-period summary from one database snapshot, preventing concurrent commits from producing internally inconsistent statement results.
- PostgreSQL/Testcontainers tests prove exact time boundaries, arithmetic, empty results, pagination, source traceability, tenant isolation, and immutable query results.

Slice 7 completion evidence: the focused Ledger suite, `./gradlew test --console=plain`, and `./gradlew check --console=plain` pass. Slice 8 reuses this Ledger-owned posting boundary while making Payment completion and exactly one Ledger posting atomic.

## Slice 8 — atomic Payment-success completion

Status: Completed. ADR-020 is accepted, and the implementation and required verification evidence pass.

Implemented boundary:

1. The internal `CompletePaymentAfterProviderSuccess` use case represents already-confirmed provider `SUCCESS`; Release 0.1 exposes no public arbitrary-success endpoint.
2. Payment owns one short PostgreSQL transaction and locks the tenant-scoped Payment row. Ledger joins that transaction through `ledger::api`; neither module accesses the other's tables, and Ledger must not use `REQUIRES_NEW`.
3. Ledger resolves ACTIVE tenant accounts for the Payment currency and posts exactly two full-amount entries: `DEBIT PROVIDER_CLEARING` and `CREDIT MERCHANT_PAYABLE`. It posts no fee, settlement movement, Reversal, correction, or partial amount.
4. The source identity is exactly `tenantId + PAYMENT + paymentId`, backed by `UNIQUE (tenant_id, source_type, source_id)`. A valid replay must verify tenant, source, currency, totals, exactly two entries, exact accounts and directions, and no compensation reference.

The state/posting matrix is exact: `PROCESSING` without a posting performs one atomic completion; `PROCESSING` with a posting is a critical inconsistency; `COMPLETED` with the exact posting returns the existing result; `COMPLETED` with no posting or a mismatched posting is a critical inconsistency; every other status is a lifecycle error. Inconsistent state is never normalized, replaced, or repaired by a late posting.

Required evidence includes the exact template, full amount and currency, missing-account rollback, Ledger-insertion rollback, Payment-update rollback, all inconsistent matrix cases, exact replay, duplicate-source prevention, coordinated concurrent completion, tenant isolation, Spring Modulith boundaries, no public completion endpoint, one joined transaction without `REQUIRES_NEW`, and no Release 0.2 infrastructure.

Release 0.2 later adds durable provider evidence, attempts, result deduplication, callbacks, inbox, outbox, and Kafka around this boundary without weakening its atomicity or replay identity.

Implementation evidence:

- `CompletePaymentAfterProviderSuccess` owns one short transaction, locks the tenant-scoped Payment row, applies the complete state/posting matrix, and updates Payment with its expected version.
- The published `ledger::api` exposes only neutral request and posting-evidence records. Ledger resolves the two required ACTIVE accounts, constructs the exact template, validates it, persists it, and supports tenant-and-source lookup without exposing internal types.
- Ledger methods require an existing transaction with `MANDATORY`; no `REQUIRES_NEW` or Release 0.2 infrastructure was added.
- PostgreSQL tests prove the exact full-amount/currency posting, missing-account rollback, injected Ledger-entry failure rollback, injected Payment-update failure rollback, exact replay, mismatch rejection, duplicate-source prevention, row-lock serialization, concurrent completion, and tenant isolation.
- Boundary tests prove there is no public completion endpoint, Payment owns the transaction, Ledger joins it, and Spring Modulith accepts only the `ledger::api` dependency.

Slice 8 completion evidence: `./gradlew test --console=plain` executed 211 tests successfully, and `./gradlew check --console=plain` passed on 2026-07-21.

## Slice 9 — release API and evidence hardening

Status: Completed. The Release 0.1 API, error, logging, architecture, demo, and traceability evidence is synchronized and executable.

Completed work:

1. Expanded the OpenAPI contract with deterministic examples, tenant-wide idempotency semantics, authentication scope, response correlation headers, and the stable RFC 7807 fields `code`, `correlationId`, `effect`, `retryable`, and `nextAction`.
2. Standardized `X-Correlation-Id` propagation, added safe problems for unsupported routes, methods, media types, and unexpected failures, and kept internal exception details out of responses.
3. Added correlation-aware operational log formatting and lifecycle logs that use stable identifiers without request payloads or secrets.
4. Added executable OpenAPI/controller alignment, error-contract, health, logging-safety, domain-independence, published-API, controller-location, and constructor-injection architecture tests.
5. Added an idempotent synthetic PostgreSQL seed and a local walkthrough for health, creation, equivalent replay, conflict, concurrency, and final-state verification.
6. Audited this plan, the README, and requirement traceability against Product Definition v1.6, the then-current v1.5 technical baseline, and ADR-016 through ADR-020. Technical Specification v1.6 now supersedes that baseline without changing Release 0.1 evidence.

No public Payment-success endpoint or Release 0.2 infrastructure was introduced. Release 0.2 implementation remains pending its own approved plan.

Slice 9 completion evidence: `./gradlew test --console=plain` executed 224 tests with zero failures, errors, or skips; `./gradlew check --console=plain`, OpenAPI YAML parsing, repository terminology/version searches, and `git diff --check` passed on 2026-07-21.

## Release-wide verification targets

- Domain and state-transition tests for tenant, payment, risk, and ledger rules
- PostgreSQL Testcontainers tests for constraints, tenancy, idempotency, locking, atomic rollback, and migrations
- Sequential and concurrent equivalent requests under the same tenant and idempotency key return the original logical result and create one Payment.
- The database enforces one Payment per `(tenant_id, idempotency_key)`; `merchant_id` is not part of that uniqueness constraint.
- Reusing a tenant and idempotency key with materially different content, including a different merchant, returns an explicit idempotency conflict. The request fingerprint includes merchant identity.
- Spring Modulith and architecture tests preventing forbidden dependencies and cross-module access
- Deterministic Risk tests proving the ADR-018 rule, score, boundary, rollback, uniqueness, optimistic-concurrency, and repeat semantics
- Ledger account tests proving ACTIVE-only creation/posting, exact account codes, mandatory tenant/currency, immutable identity, non-deletion, tenant/code/currency uniqueness, atomic validation failure, derived immutable history, and PostgreSQL constraints
- Slice 8 tests proving the exact full-amount two-entry Payment-success template, complete replay evidence, every state/posting matrix branch, joined-transaction rollback, source uniqueness, coordinated concurrency, tenant isolation, and module boundaries
- OpenAPI contract and RFC 7807 error examples
- Structured logs for release API operations without secrets or sensitive payloads
- `./gradlew test` and `./gradlew check` passing

## Known risks

- Idempotency implemented only in Java would race; a PostgreSQL unique constraint on `(tenant_id, idempotency_key)` and concurrency tests are mandatory.
- Payment implementation must preserve the exact ADR-016 lifecycle and ownership boundaries; provider, Reversal, and Reconciliation progress must not expand `PaymentStatus`.
- Idempotency comparison must distinguish equivalent request replay from materially different content. In particular, changing the merchant under the same tenant and key must return a conflict rather than create another Payment.
- Payment completion and Ledger posting can diverge if transaction ownership is unclear; Payment must own one transaction and Ledger must join it through `ledger::api` without `REQUIRES_NEW`.
- Treating source existence as a valid replay can conceal a wrong posting; validate the complete ADR-020 posting shape and raise a typed critical consistency error on any mismatch.
- Risk evidence and the Payment decision can diverge if Transaction 2 is split or owned by Risk; Payment orchestration must commit both effects atomically.
- A hidden or global Risk fallback could silently change tenant decisions; require one explicitly persisted active profile and typed configuration failures.
- Tenant IDs can be forgotten in later tables; enforce non-null ownership and tenant-scoped uniqueness from the first migration for each module.
- Ledger posting can cross tenant or currency boundaries if account lookup is treated as a reference-only check; validate every account inside the posting transaction and roll back the complete posting on any violation.
- Broad parallel coding could create incompatible payment/ledger contracts; serialize those shared boundaries.

## Release 0.1 definition of done

- Exit outcome is demonstrated by automated tests, including concurrency and forced failure.
- Every requirement touched by Release 0.1 maps to its exact implemented, partial, or planned evidence in traceability; partial evidence is never reported as full implementation.
- No Release 0.2+ technology has been introduced.
- Migrations install cleanly on PostgreSQL.
- Module boundaries and domain independence are automatically verified.
- API, failure behaviour, logs, setup guidance, and known limitations are documented.
- No code/document divergence or unapproved architectural deviation remains.
