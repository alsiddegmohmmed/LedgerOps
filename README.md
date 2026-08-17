# LedgerOps

**A multi-tenant transaction processing and financial operations platform built with Java and Spring Boot.**

LedgerOps coordinates payment processing across risk evaluation, provider execution, immutable financial accounting, reconciliation, investigation, and operational controls.

The system is designed around the failure modes that make transactional platforms difficult to build correctly: concurrent requests, duplicate delivery, ambiguous provider outcomes, partial failures, financial consistency, tenant isolation, recovery after process crashes, and traceable operational intervention.

## System overview

A payment entering LedgerOps moves through a controlled lifecycle:

```text
Payment request
      │
      ▼
Validation + Idempotency
      │
      ▼
Risk Evaluation
      │
      ▼
Provider Processing
      │
      ▼
Provider Result
      │
      ├── failure / recovery
      │
      ▼
Atomic Payment Completion
      │
      ▼
Double-Entry Ledger
      │
      ▼
Settlement Reconciliation
      │
      ▼
Operational Investigation
```

The platform preserves the evidence required to explain that lifecycle: payment state, risk decisions, provider attempts and results, ledger movements, settlement records, discrepancies, cases, and audit history.

## Architecture

LedgerOps uses a **Spring Boot modular monolith** for the transactional core.

Modules own their domain model and PostgreSQL schema and communicate through explicit published interfaces rather than directly accessing another module's persistence.

```text
                         ┌───────────────────────┐
                         │    Operations Web     │
                         │       Next.js         │
                         └───────────┬───────────┘
                                     │ HTTPS
                                     ▼
┌──────────────────────────────────────────────────────────────┐
│                    LedgerOps Core Platform                   │
│                                                              │
│  tenancy   merchant   payment   risk   ledger   provider     │
│                                                              │
│  identity  audit      reconciliation  casework  reporting    │
└──────────────┬─────────────────┬─────────────────┬───────────┘
               │                 │                 │
               ▼                 ▼                 ▼
          PostgreSQL           Kafka          Redis / MinIO
               ▲
               │
               │ signed HTTP / webhooks / settlement files
               │
       ┌───────┴────────┐
       │ Provider System │
       └─────────────────┘
```

The Provider Simulator is deployed as a separate Spring Boot application with its own PostgreSQL database. It exercises the external-provider boundary through signed HTTP requests, asynchronous webhooks, status recovery, duplicate delivery, delayed results, failures, and settlement records without sharing Core persistence.

### Internal module structure

Modules follow the same dependency direction:

```text
api
 │
 ▼
application
 │
 ▼
domain

infrastructure ──► application/domain ports
```

The domain layer remains independent of Spring Web, JPA, Kafka, and infrastructure implementations.

Spring Modulith and architecture tests enforce module ownership and dependency boundaries.

## Transactional correctness

Financial correctness is enforced at both the application and database boundaries.

### Payment idempotency

Payment creation uses a tenant-wide idempotency namespace:

```text
tenantId + idempotencyKey
```

Equivalent repeated requests resolve to one logical Payment.

Reusing the same identity with materially different content produces an explicit idempotency conflict.

PostgreSQL enforces the uniqueness boundary so correctness does not depend solely on application-level checks.

### Atomic payment completion

A Payment reaches `COMPLETED` only after definitive provider success.

Completion and its financial effect share one PostgreSQL transaction:

```text
Lock Payment
    │
    ▼
Validate Payment + existing Ledger state
    │
    ▼
Post balanced Ledger transaction
    │
    ▼
Payment.complete()
    │
    ▼
COMMIT
```

For a successful payment, Ledger posts exactly:

```text
DEBIT   PROVIDER_CLEARING
CREDIT  MERCHANT_PAYABLE
```

for the complete Payment amount and currency.

If any part of the operation fails, the Payment transition and Ledger posting roll back together.

Database uniqueness on the financial source prevents duplicate postings:

```text
UNIQUE (tenant_id, source_type, source_id)
```

A replay is accepted only when the complete existing posting matches the expected financial transaction. Finding a record with the correct identifier is not considered sufficient evidence.

### Immutable ledger

Ledger transactions and entries are append-only.

Every posted transaction:

* belongs to one tenant;
* uses valid tenant-owned accounts;
* contains debit and credit entries;
* balances by currency;
* has immutable financial history; and
* retains the source responsible for the movement.

Corrections create new compensating transactions rather than modifying previously posted financial records.

## Distributed provider processing

Provider processing uses **at-least-once delivery**.

LedgerOps does not depend on exactly-once messaging guarantees.

Instead, duplicate-safe processing is built from:

* transactional outbox records;
* Kafka;
* consumer inbox records;
* stable business identities;
* database uniqueness constraints;
* fenced worker leases;
* immutable Provider evidence; and
* idempotent consumers.

```text
Payment transaction
      │
      ├── Payment Attempt
      ├── Payment -> PROCESSING
      └── Outbox command
                │
                ▼
              Kafka
                │
                ▼
       Provider work record
                │
                ▼
     External HTTP interaction
                │
                ▼
      Provider result evidence
                │
                └── Outbox result
                          │
                          ▼
                        Kafka
                          │
                          ▼
                 Payment result handling
```

External provider calls occur outside database transactions.

Committed work is persisted before the network boundary so a process crash cannot silently lose the operation.

## Ambiguous provider outcomes

A timeout after request transmission is not treated as a definitive failure.

LedgerOps distinguishes:

```text
Definitive success
Definitive decline
Permanent failure
Safe-to-resubmit failure
Pending / accepted state
Unknown outcome
```

An unknown outcome enters status recovery instead of blindly submitting another financial operation.

Automatic retries are permitted only when durable evidence establishes that another provider-side transaction cannot be created.

Attempt limits, status-recovery limits, retry schedules, and provider-work leases are explicit and tested.

If recovery is exhausted without a trustworthy final result, the Payment remains `PROCESSING` and the unresolved condition remains visible for operational intervention.

## Risk processing

Risk evaluation occurs before Provider processing.

Risk configuration is versioned per tenant and every evaluation preserves:

* profile identity and version;
* rules evaluated;
* triggered rules;
* score contributions;
* uncapped score;
* final score;
* decision; and
* evaluation timestamp.

The current deterministic scoring model supports:

```text
APPROVE
MANUAL_REVIEW
REJECT
```

A configuration or processing failure cannot silently approve or reject a Payment.

The Payment remains in its durable validation state and the failure is surfaced explicitly.

## Multi-tenancy and authorization

Tenant ownership is mandatory throughout the system.

Tenant-owned records include a non-null `tenant_id`, and repositories and business operations are tenant-scoped.

Cross-module persistence access is prohibited.

Identity and authorization use Keycloak with OAuth 2.0 / OpenID Connect. Application membership, permissions, merchant scope, and authorization data remain under LedgerOps ownership.

The Operations Web uses a backend-for-frontend boundary so browser clients do not directly own backend access tokens.

Redis may support session or derived operational state, but never financial or authorization truth.

## Reconciliation and financial operations

Settlement data is ingested independently from the transactional Payment flow.

LedgerOps preserves raw settlement evidence and performs reconciliation against internal records to identify:

* matched transactions;
* missing records;
* amount differences;
* status differences; and
* other settlement discrepancies.

Reconciliation never directly edits Payment or Ledger persistence.

Discrepancies move through controlled operational workflows, and corrections create new financial evidence rather than rewriting history.

## Failure and recovery model

Failures are represented explicitly rather than hidden behind generic retry behaviour.

The platform covers scenarios including:

* concurrent duplicate Payment requests;
* duplicate Kafka delivery;
* duplicate and out-of-order Provider webhooks;
* conflicting Provider results;
* Provider timeouts;
* Kafka outages;
* publisher crashes after Kafka acknowledgement;
* expired worker leases;
* stale workers;
* poison messages;
* incomplete Provider recovery;
* Ledger consistency violations; and
* transactional rollback failures.

Recovery mechanisms preserve stable identities and durable evidence so that retries do not create duplicate business or financial effects.

## Observability

LedgerOps exposes operational evidence through:

* structured logs with correlation identifiers;
* OpenTelemetry trace propagation;
* Prometheus metrics;
* Grafana dashboards;
* Provider health measurements;
* messaging backlog and failure measurements;
* dead-letter visibility; and
* documented operational runbooks.

Business identifiers are not used as unbounded metric labels.

PostgreSQL remains the transactional source of truth; telemetry is observational only.

## Operations Web

The Operations Web provides a tenant-scoped operational view across the platform.

Current capabilities include:

* payment volume and outcome metrics;
* success and failure rates;
* manual-review workload;
* reconciliation discrepancies;
* unresolved investigation cases;
* Provider health;
* source-record drill-down;
* merchant filtering;
* tenant timezone presentation; and
* authenticated Core access through the server-side BFF.

The interface is intentionally operational: dashboard values link back to the records responsible for them instead of existing as isolated metrics.

## Technology

### Backend

* Java 21
* Spring Boot
* Spring Modulith
* Spring Data JPA
* PostgreSQL
* Flyway
* Apache Kafka
* Spring Batch
* Resilience4j

### Identity and platform

* Keycloak
* Redis
* S3-compatible object storage / MinIO

### Observability

* OpenTelemetry
* Prometheus
* Grafana

### Frontend

* Next.js
* React
* TypeScript
* pnpm

### Verification

* JUnit 5
* Testcontainers
* architecture and module-boundary tests
* PostgreSQL integration tests
* Kafka integration tests
* concurrency and recovery tests
* API and contract verification
* browser-level operational workflow tests

## Repository structure

```text
.
├── src/main/java/com/ledgerops
│   ├── identity
│   ├── tenancy
│   ├── merchant
│   ├── customer
│   ├── payment
│   ├── messaging
│   ├── provider
│   ├── risk
│   ├── ledger
│   ├── reconciliation
│   ├── casework
│   ├── audit
│   └── reporting
│
├── applications
│   ├── provider-simulator
│   └── operations-web
│
├── packages
│   ├── event-contracts
│   └── provider-contracts
│
├── observability
│   ├── prometheus
│   └── grafana
│
├── src/main/resources/db/migration
├── src/test/java/com/ledgerops
│
└── docs
    ├── product
    ├── architecture
    ├── adr
    ├── api
    ├── plans
    ├── requirements
    ├── runbooks
    └── reviews
```

## Engineering guarantees

The architecture is built around a small set of system-wide guarantees:

1. PostgreSQL is the authoritative source of transactional and financial state.
2. Every completed Payment has exactly one corresponding valid financial posting.
3. Every posted Ledger transaction balances.
4. Posted financial history is immutable.
5. Duplicate requests, events, and webhooks cannot create duplicate financial effects.
6. Provider ambiguity is recovered before another potentially unsafe submission is allowed.
7. External network calls are separated from database transaction boundaries.
8. Tenant ownership is mandatory for tenant-scoped data.
9. Module persistence remains private to its owning module.
10. Operational corrections preserve the original evidence.

These guarantees are enforced through domain rules, PostgreSQL constraints, transaction boundaries, idempotency identities, concurrency controls, architecture verification, and integration tests.

## Release status

| Release | Capability                                                                      | Status                            |
| ------- | ------------------------------------------------------------------------------- | --------------------------------- |
| 0.1     | Transactional Core                                                              | Complete                          |
| 0.2     | Distributed Provider Processing                                                 | Complete                          |
| 0.3     | Identity, financial operations, reconciliation, audit, and Operations Web       | Final release closure in progress |
| 1.0     | Deployment, security hardening, operational verification, and release packaging | Planned                           |

## Running the verification suite

Prerequisites:

* Java 21
* Docker

macOS / Linux:

```bash
./gradlew test
./gradlew check
```

Windows:

```powershell
.\gradlew.bat test
.\gradlew.bat check
```

Testcontainers provisions the real infrastructure required by integration tests, including PostgreSQL and Kafka where applicable.

## Documentation

The repository maintains explicit engineering evidence for system behaviour and architectural decisions:

* [Product Definition](docs/product/LedgerOps_Product_Definition_Official_v1.6.docx)
* [Technical Design and Architecture Specification](docs/architecture/LedgerOps_Technical_Design_and_Architecture_Specification_v1.6.docx)
* [Architecture Decision Records](docs/adr)
* [Requirement Traceability](docs/requirements/TRACEABILITY.md)
* [API Contracts](docs/api)
* [Operational Runbooks](docs/runbooks)
* [Implementation Plans](docs/plans)

Material changes to approved product behaviour, ownership boundaries, consistency guarantees, security architecture, or technology decisions are recorded through ADRs.

---

**Correctness before throughput. Modularity before distribution. Evidence over claims.**
