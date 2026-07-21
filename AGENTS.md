# LedgerOps agent instructions

## Mission and authority

LedgerOps is a production-style portfolio project for learning and demonstrating Java, Spring Boot, PostgreSQL, domain-driven design, modular monoliths, and financial correctness.

Read these documents before making implementation decisions:

1. `docs/product/LedgerOps_Product_Definition_Official_v1.6.docx` defines what the product must do.
2. `docs/architecture/LedgerOps_Technical_Design_and_Architecture_Specification_v1.6.docx` defines the approved design.
3. `docs/plans/release-0.2-distributed-processing.md` defines the current sequence and status.
4. `docs/requirements/TRACEABILITY.md` maps requirements to evidence.

Precedence is product definition, technical specification, approved ADRs, then implementation plans. Code and plans must not silently contradict a higher-authority source.

ADR-020 is accepted and remains the non-negotiable Payment-success posting and completion boundary. ADR-021 is accepted and authorizes Release 0.2 implementation after completion of Slice 0.

If implementation evidence exposes a conflict or impractical decision, stop before changing the design. Describe the exact conflict, recommend one replacement with trade-offs, and wait for approval. Record an approved material change as an ADR.

## Current scope

The active milestone is **Release 0.2 — Distributed Processing**. Slices 0–5 are complete; Slice 6 is the next pending slice. Follow accepted ADR-021 and `docs/plans/release-0.2-distributed-processing.md`; do not implement later-slice behavior early.

Allowed now:

- Java 21, Spring Boot, Spring Modulith, Gradle Kotlin DSL
- PostgreSQL, Flyway, Spring Data JPA
- tenant, merchant, and customer foundations
- payment creation, idempotency, and controlled state transitions
- synchronous deterministic Risk evaluation using only the ADR-018 `PAYMENT_AMOUNT_THRESHOLD` model
- strict double-entry ledger and an internal atomic Payment-success completion using exactly full-amount `DEBIT PROVIDER_CLEARING` and `CREDIT MERCHANT_PAYABLE` in the Payment currency
- HTTP APIs, OpenAPI, RFC 7807 problems, structured logs
- JUnit, Testcontainers with PostgreSQL, ArchUnit, and Spring Modulith tests
- Kafka with at-least-once delivery and transactional outbox/inbox
- Payment Attempts for Payments and Provider processing/recovery
- a separate Provider Simulator application and database
- Resilience4j, OpenTelemetry, Prometheus, and initial Grafana dashboards

Release 0.2 still excludes Keycloak, identity and authorization, Reversal, settlement/reconciliation, casework/corrections, public manual replay, a polished frontend, Kubernetes, Terraform, AWS deployment, applied AI, and Redis without a separately approved need. Release 0.3 introduces Keycloak, identity, tenant membership, permissions, merchant scope, authorization, and tenant-isolation enforcement. Release 1.0 completes security hardening and release evidence. Do not add a technology before its active slice.

## Non-negotiable correctness rules

- PostgreSQL is the transactional source of truth.
- A payment becomes `COMPLETED` only when exactly one corresponding balanced ledger transaction is posted in the same database transaction.
- Every posted ledger transaction balances by currency and has at least one debit and one credit.
- Posted ledger entries are immutable. Corrections use compensating transactions.
- Duplicate requests must not create duplicate payments or financial effects.
- Payment API idempotency is tenant-wide. The uniqueness boundary is exactly `tenantId + idempotencyKey`; `merchantId` is request content, not part of that boundary.
- Tenant context is mandatory for tenant-owned data. Suspended tenants retain readable history but cannot create new activity.
- Money uses `BigDecimal` plus an explicit currency; never use floating point.
- External network calls never occur inside long database transactions.
- Provider timeouts are ambiguous outcomes, not automatic failures.
- Time-dependent domain behaviour receives an injected `Clock`.
- Release 0.1 Risk scoring uses versioned tenant profiles, integer scores from 0 through 100, and the exact ADR-018 thresholds and decision boundaries.
- Risk configuration or processing failure leaves Payment `VALIDATING` and persists no partial Risk evidence or Payment decision.
- Release 0.1 Ledger accounts use exactly `ACTIVE` and the ADR-019 account-code catalog. They have no lifecycle or deletion.
- Ledger account uniqueness is exactly `tenantId + accountCode + currency`; every posting validates account existence, tenant, currency, and `ACTIVE` status atomically.
- Preserve the exact ADR-020 completion transaction, Ledger posting identity, replay verification, and rollback behavior.
- A Payment cannot complete from a Provider result without matching durable Provider evidence.
- Provider HTTP calls occur outside database transactions. `UNKNOWN` enters status recovery and never triggers blind resubmission.
- Release 0.2 supports exactly `providerId = SIMULATOR`; every attempt reuses the Payment-derived Provider idempotency identity.
- Messaging producer names are the closed values `payment` and `provider`. Business outbox identity, inbox identity, canonical hashes, and stable message IDs follow ADR-021 exactly.
- Outbox and Provider work use fenced leases. A stale lease holder cannot mutate reclaimed work.
- The module direction is exactly `Provider -> messaging::api`, `Payment -> provider::api`, and `Payment -> messaging::api`; Provider never depends on Payment.

## Architecture and coding rules

- Keep one Spring Boot modular monolith with explicit Spring Modulith boundaries.
- Each module owns its schema and tables. Cross-module table access is forbidden.
- Payment owns Risk orchestration and calls only the published `risk::api`; Risk never reads or mutates Payment data directly.
- Organize modules using `api`, `application`, `domain`, and `infrastructure` packages as those layers become necessary.
- Domain code must not depend on Spring, JPA, HTTP, messaging, or infrastructure implementations.
- Use explicit domain models, value objects, small cohesive classes, constructor injection, typed errors, and deterministic behaviour.
- Prefer database constraints for critical invariants in addition to Java validation.
- Avoid generic base services/repositories, God classes, anemic models, shared mutable entities, broad exception swallowing, and speculative abstractions.
- Released Flyway migrations are immutable. Correct them with a later migration.

## Task workflow

Before substantial implementation:

1. Inspect the repository and read the applicable requirements and plan.
2. State the exact scope, expected files, assumptions, risks, and dependencies.
3. Propose the smallest correct sequence. Limit user guidance to four steps at a time and explain the Java or architecture lesson in plain language.

During implementation:

- Work in small, reviewable slices and do not modify unrelated files.
- One implementation owner controls each vertical slice. Parallel agents may review or investigate independent concerns only when their contracts do not overlap.
- A vertical slice is not complete until domain rules, persistence, migration, API contract, failure behaviour, observability, tests, and relevant documentation are covered.
- Never present placeholders or unverified claims as complete.

After implementation, report:

- What changed and why.
- Exact verification commands and their results.
- What remains incomplete.
- Any deviation from the authoritative documents, normally `None`.

## Verification

Use the Gradle wrapper:

```bash
./gradlew test
./gradlew check
```

Run narrower tests while iterating, then the relevant full suite before completion. Tests that depend on PostgreSQL behaviour must use Testcontainers; do not substitute H2. Prioritize invariant, lifecycle, idempotency, concurrency, tenant-isolation, migration, and module-boundary tests. Do not mock infrastructure when the real behaviour is the subject of the test.

## Git and documentation

- Keep commits small and intentional. Do not rewrite unrelated history.
- Do not push, publish, merge, or open a pull request unless explicitly requested.
- Update `docs/requirements/TRACEABILITY.md` and the active plan when a slice changes requirement status.
- Use `docs/plans/PLAN_TEMPLATE.md`, `docs/adr/ADR_TEMPLATE.md`, and `docs/reviews/CODE_REVIEW_CHECKLIST.md`.
- Correctness blockers include ledger imbalance, duplicate financial posting, payment completion without a ledger posting, cross-tenant disclosure, authorization bypass, and an unrecoverable migration.
