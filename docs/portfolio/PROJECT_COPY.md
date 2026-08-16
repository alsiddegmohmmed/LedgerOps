# LedgerOps project copy

Use this file as the canonical source for portfolio cards, the case-study hero,
resume entries, repository descriptions, and interview introductions. Keep the
technical claims aligned with [EVIDENCE_MAP.md](EVIDENCE_MAP.md).

## Project identity

**Name:** LedgerOps

**Category:** Financial backend and operations platform

**Headline:** Engineering financial correctness under failure

**Tagline:** One financial outcome—even when requests race, messages repeat,
providers time out, and settlement records disagree.

**Role:** Product definition, architecture, backend and frontend implementation,
test design, release planning, and technical documentation.

## Hero copy

### Recommended hero

**LedgerOps**

**Engineering financial correctness under failure**

A production-style financial-operations platform built with Java, Spring Boot,
PostgreSQL, and Kafka. LedgerOps demonstrates transactional correctness,
duplicate-safe distributed processing, immutable double-entry accounting,
Provider failure recovery, settlement Reconciliation, and Tenant-scoped
operations.

### Compact hero

A Java and Spring Boot financial-operations platform designed around
idempotency, atomic accounting, failure recovery, Reconciliation, and auditable
Tenant isolation.

## Project-card descriptions

### 35-word version

LedgerOps is a production-style financial-operations platform that demonstrates
idempotent Payment processing, atomic double-entry accounting, duplicate-safe
Kafka workflows, Provider ambiguity recovery, full Reversal, deterministic
Reconciliation, and Tenant-scoped operations.

### 75-word version

LedgerOps explores how a financial backend preserves one defensible outcome
when requests race, messages repeat, Providers time out, and settlement evidence
conflicts with internal state. Built with Java 21, Spring Boot, PostgreSQL,
Kafka, Keycloak, Spring Batch, and Next.js, it connects Payment intake,
deterministic Risk, durable Provider processing, immutable Ledger entries,
full-payment Reversal, Reconciliation, Cases, controlled corrections, and an
Operations Web application.

### 150-word version

LedgerOps is a production-style, multi-tenant financial-operations platform
built to demonstrate correctness under concurrency and failure. Equivalent
Payment requests converge through PostgreSQL-enforced idempotency, while
conflicting reuse is rejected. Provider commands and results use Kafka
at-least-once delivery with transactional outbox/inbox records, stable
identities, fenced work, and idempotent effects. A Payment reaches `COMPLETED`
only when its exact balanced Ledger posting commits in the same PostgreSQL
transaction. Full Reversals and narrow settlement corrections preserve history
through compensating transactions rather than mutation. Settlement files are
stored immutably and processed into deterministic Reconciliation runs,
discrepancies, Cases, and stable posting instructions. Keycloak authenticates,
while current Tenant, membership, permission, Merchant-scope, and credential
authorization remains in Core PostgreSQL. The project includes a Next.js
Operations Web application and extensive PostgreSQL, Kafka, contract,
architecture, migration, scale, and browser verification.

## Resume entry

### Recommended version

**LedgerOps — Financial backend and operations platform**

Java 21, Spring Boot, Spring Modulith, PostgreSQL, Kafka, Keycloak, Redis, Spring
Batch, Next.js, Testcontainers

- Designed and implemented a multi-tenant financial-operations platform covering
  Payment intake, deterministic Risk, durable Provider processing, immutable
  double-entry accounting, full Reversal, settlement Reconciliation, Cases, and
  controlled corrections.
- Enforced Tenant-wide idempotency, duplicate-safe at-least-once messaging, and
  one atomic Payment/Ledger completion boundary through stable identities,
  PostgreSQL constraints, transactional outbox/inbox records, and exact replay
  validation.
- Modelled Provider timeouts as ambiguous outcomes with durable evidence,
  bounded status recovery, and safe-resubmission controls instead of automatic
  failure or blind retry.
- Verified the system with a recorded 740-test backend gate, V1–V45 Flyway
  installation and upgrade coverage, bounded 100,000-record batch tests,
  contract fixtures, frontend tests, and Playwright scenarios.

### Compact resume version

- Built LedgerOps, a Java/Spring Boot financial-operations platform with
  PostgreSQL-enforced idempotency, Kafka outbox/inbox delivery, immutable
  double-entry accounting, Provider recovery, Reversal, and deterministic
  settlement Reconciliation.
- Designed transaction, replay, concurrency, and correction boundaries that
  prevent duplicate financial effects and preserve Tenant-scoped audit evidence;
  recorded 740 passing backend tests plus migration, scale, contract, and
  browser verification.

## Repository description

### GitHub short description

Production-style financial operations with Java, Spring Boot, PostgreSQL, Kafka,
immutable accounting, and failure-safe processing.

### README opening

LedgerOps is a production-style financial-operations platform about preserving
correctness when requests race, messages repeat, Providers time out, and
settlement records disagree. It combines a Java/Spring Boot modular monolith, a
separate Provider Simulator, and a Next.js Operations Web application to make
financial invariants, failure recovery, and operational evidence inspectable.

## Interview introductions

### 30-second version

I built LedgerOps to go beyond a typical Payment CRUD project. The system asks
what happens when duplicate requests arrive concurrently, Kafka redelivers a
message, a Provider times out after accepting a request, or settlement data
conflicts with internal state. I used PostgreSQL-enforced idempotency, atomic
Payment and Ledger completion, outbox/inbox messaging, durable Provider
evidence, immutable Reconciliation, and compensating transactions so those
failures converge safely or become explicit investigation work.

### Two-minute version

LedgerOps is a production-style financial-operations platform I built with Java,
Spring Boot, PostgreSQL, Kafka, Keycloak, Spring Batch, and Next.js. I started
from financial invariants rather than technologies. A completed Payment must
have exactly one balanced Ledger posting. Equivalent retries must return one
logical Payment. Duplicate delivery must not duplicate a financial effect, and
a Provider timeout must remain unknown until evidence resolves it.

The Core Platform is a modular monolith because Payment and Ledger need a
valuable local transaction boundary, while external Provider work uses durable
outbox/inbox handoffs and at-least-once Kafka delivery. Provider calls happen
outside database transactions and use stable identities, immutable evidence,
fenced workers, and bounded status recovery. Reversals and settlement
corrections create compensating entries instead of editing Ledger history.

The later workflows ingest immutable settlement evidence, create deterministic
Reconciliation runs, classify discrepancies, open Cases, and produce stable
posting or correction instructions. Keycloak handles authentication, while Core
PostgreSQL evaluates current Tenant and Merchant authorization. The recorded
release gate includes 740 backend tests, migration and 100,000-record batch
evidence, frontend tests, and Playwright scenarios. The project taught me to
design identity, ownership, transaction boundaries, replay, and observability as
one consistency model.

## Technical focus areas

Use the tags that match the target role. Do not display every tag at once.

### Backend engineering

- Java 21
- Spring Boot
- domain-driven design
- Spring Modulith
- PostgreSQL
- Flyway
- REST and RFC 7807
- concurrency control
- idempotency
- double-entry accounting

### Distributed systems

- Apache Kafka
- transactional outbox
- consumer inbox
- at-least-once delivery
- idempotent consumers
- fencing and leases
- ambiguity recovery
- HMAC contracts
- OpenTelemetry

### Financial operations

- immutable Ledger
- compensating transactions
- full-payment Reversal
- settlement ingestion
- deterministic Reconciliation
- discrepancy Casework
- controlled correction
- audit evidence

### Product and platform

- multi-tenancy
- Keycloak OIDC/OAuth
- permission and Merchant scope
- Next.js backend-for-frontend
- Redis sessions
- Spring Batch
- MinIO
- Prometheus and Grafana
- Testcontainers
- Playwright

## Credibility statement

LedgerOps is a simulation and portfolio project. It uses synthetic data and a
Provider Simulator, does not process real money, and does not claim regulatory
certification. Its value is the explicit, executable treatment of financial
correctness, concurrency, failure, recovery, and operational investigation.
