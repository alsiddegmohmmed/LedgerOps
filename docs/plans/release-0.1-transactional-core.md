# Release 0.1 plan: Transactional Core

Status: Active  
Last updated: 2026-07-18  
Authority: Product Definition v1.1; Technical Specification v1.0 §14.1

## Exit outcome

Release 0.1 ends when concurrent equivalent requests create one logical payment, conflicting reuse of an idempotency key is rejected, and every completed payment has exactly one balanced immutable ledger posting committed atomically with payment completion.

This release remains a Spring Boot modular monolith backed by PostgreSQL. Kafka, outbox/inbox processing, Redis, Keycloak, Kubernetes, reconciliation, a polished frontend, and AI are explicitly deferred.

## Current repository evidence

Completed:

- Gradle/Spring Boot Java 21 application foundation
- Spring Modulith verification test and `tenancy` module declaration
- `Tenant`, `TenantId`, and lifecycle rules as plain domain Java
- Flyway-created `tenancy.tenants` table with critical checks and optimistic version column
- explicit domain/JPA mapping through a persistence adapter
- PostgreSQL Testcontainers schema and persistence tests

Important limitation: this is a foundation, not a complete TEN-01 vertical slice. There is no tenancy application service, HTTP contract, authorization, structured API error handling, or audit trail yet.

## Ordered implementation slices

| Order | Slice | Main evidence | Status |
|---:|---|---|---|
| 0 | Codex operating documents and repository workflow | Agent rules, templates, traceability, clean build | Completed |
| 1 | Complete tenant management basics | lifecycle application use cases, API contract, RFC 7807 failures, PostgreSQL integration tests | Next |
| 2 | Merchant foundation | tenant-owned aggregate, schema, repository, isolation tests | Planned |
| 3 | Customer foundation | tenant/merchant ownership, schema, repository, isolation tests | Planned |
| 4 | Money and payment domain | value objects, approved state machine, invariant tests | Planned |
| 5 | Payment creation and idempotency | unique tenant/merchant/key boundary, request fingerprint, concurrent PostgreSQL test | Planned |
| 6 | Synchronous risk rules | deterministic rule outcomes and approve/reject/review evidence | Planned |
| 7 | Double-entry ledger | accounts, balanced immutable postings, database constraints, property/integration tests | Planned |
| 8 | Atomic payment completion | one PostgreSQL transaction, narrow locking, forced-failure rollback and concurrency tests | Planned |
| 9 | Release API and evidence hardening | OpenAPI, structured logs, architecture tests, README/demo instructions, traceability audit | Planned |

Only one implementation slice should change shared domain contracts or schemas at a time. Reviews of architecture, persistence/concurrency, or tests may run independently after a stable diff exists.

## Slice 1 — smallest next step

Outcome: expose the already-implemented tenant lifecycle through an application boundary without weakening the domain/persistence separation.

Before coding, resolve one scope point against the baselines: TEN-01 includes a designated initial Merchant Admin, while Release 0.1 explicitly excludes Keycloak. The application contract must preserve that future requirement without introducing identity infrastructure early. If the baselines do not provide a compatible Release 0.1 representation, raise the conflict before implementation rather than inventing one.

Expected work:

1. Define tenant application use cases and typed outcomes for create, activate, suspend, archive, and read.
2. Add an HTTP contract with validation and RFC 7807 problems; keep authorization clearly marked as incomplete rather than simulated as secure.
3. Add PostgreSQL-backed tests for lifecycle persistence, duplicate names, suspended write prevention at the application boundary, and readable history.
4. Update traceability, API documentation, and the plan with exact verification evidence.

## Release-wide verification targets

- Domain and state-transition tests for tenant, payment, risk, and ledger rules
- PostgreSQL Testcontainers tests for constraints, tenancy, idempotency, locking, atomic rollback, and migrations
- Concurrent duplicate-request proof using final database state
- Spring Modulith and architecture tests preventing forbidden dependencies and cross-module access
- OpenAPI contract and RFC 7807 error examples
- `./gradlew test` and `./gradlew check` passing

## Known risks

- Idempotency implemented only in Java would race; PostgreSQL uniqueness and concurrency tests are mandatory.
- Payment completion and ledger posting can diverge if transaction ownership is unclear; one application service must own the atomic boundary.
- Tenant IDs can be forgotten in later tables; enforce non-null ownership and tenant-scoped uniqueness from the first migration for each module.
- Broad parallel coding could create incompatible payment/ledger contracts; serialize those shared boundaries.

## Release 0.1 definition of done

- Exit outcome is demonstrated by automated tests, including concurrency and forced failure.
- Every implemented requirement maps to passing evidence in traceability.
- No Release 0.2+ technology has been introduced.
- Migrations install cleanly on PostgreSQL.
- Module boundaries and domain independence are automatically verified.
- API, failure behaviour, logs, setup guidance, and known limitations are documented.
- No code/document divergence or unapproved architectural deviation remains.
