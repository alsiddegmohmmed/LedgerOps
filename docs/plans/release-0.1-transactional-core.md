# Release 0.1 plan: Transactional Core

Status: Active  
Last updated: 2026-07-19
Authority: LedgerOps Product Definition v1.3 and LedgerOps Technical Design and Architecture Specification v1.2 only

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
- validated HTTP contract with `201` creation, `200` equivalent replay, RFC 7807 validation/reference/idempotency failures, correlated trace IDs, and structured lifecycle logs
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
| 6 | Synchronous risk rules | evaluated and triggered rules, score contributions, bounded thresholds, approve/reject/review evidence | Planned |
| 7 | Double-entry ledger | tenant-scoped accounts, balanced immutable postings, database constraints, property/integration tests | Planned |
| 8 | Atomic payment completion | one PostgreSQL transaction, narrow locking, forced-failure rollback and concurrency tests | Planned |
| 9 | Release API and evidence hardening | OpenAPI, structured logs, architecture tests, README/demo instructions, traceability audit | Planned |

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

Documentation reconciliation is complete through ADR-016, ADR-017, Product Definition v1.3, and Technical Specification v1.2.

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

Risk evaluation remains the next slice. Payment completion still cannot be claimed until the ledger and atomic-completion slices provide their required evidence.

## Release-wide verification targets

- Domain and state-transition tests for tenant, payment, risk, and ledger rules
- PostgreSQL Testcontainers tests for constraints, tenancy, idempotency, locking, atomic rollback, and migrations
- Sequential and concurrent equivalent requests under the same tenant and idempotency key return the original logical result and create one Payment.
- The database enforces one Payment per `(tenant_id, idempotency_key)`; `merchant_id` is not part of that uniqueness constraint.
- Reusing a tenant and idempotency key with materially different content, including a different merchant, returns an explicit idempotency conflict. The request fingerprint includes merchant identity.
- Spring Modulith and architecture tests preventing forbidden dependencies and cross-module access
- OpenAPI contract and RFC 7807 error examples
- Structured logs for release API operations without secrets or sensitive payloads
- `./gradlew test` and `./gradlew check` passing

## Known risks

- Idempotency implemented only in Java would race; a PostgreSQL unique constraint on `(tenant_id, idempotency_key)` and concurrency tests are mandatory.
- Payment implementation must preserve the exact ADR-016 lifecycle and ownership boundaries; provider, Reversal, and Reconciliation progress must not expand `PaymentStatus`.
- Idempotency comparison must distinguish equivalent request replay from materially different content. In particular, changing the merchant under the same tenant and key must return a conflict rather than create another Payment.
- Payment completion and ledger posting can diverge if transaction ownership is unclear; one application service must own the atomic boundary.
- Tenant IDs can be forgotten in later tables; enforce non-null ownership and tenant-scoped uniqueness from the first migration for each module.
- Broad parallel coding could create incompatible payment/ledger contracts; serialize those shared boundaries.

## Release 0.1 definition of done

- Exit outcome is demonstrated by automated tests, including concurrency and forced failure.
- Every requirement touched by Release 0.1 maps to its exact implemented, partial, or planned evidence in traceability; partial evidence is never reported as full implementation.
- No Release 0.2+ technology has been introduced.
- Migrations install cleanly on PostgreSQL.
- Module boundaries and domain independence are automatically verified.
- API, failure behaviour, logs, setup guidance, and known limitations are documented.
- No code/document divergence or unapproved architectural deviation remains.
