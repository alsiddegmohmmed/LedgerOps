# LedgerOps portfolio package

This package turns the completed LedgerOps product into one evidence-backed
portfolio story. It is written for engineering managers, senior backend and
platform engineers, technical recruiters, and interviewers who need to
understand the project without reading the entire repository.

## Portfolio positioning

**Category:** Financial backend and operations platform

**Primary story:** LedgerOps demonstrates how a financial system preserves
correctness when requests are repeated, messages are delivered more than once,
providers return ambiguous outcomes, and settlement evidence disagrees with
internal state.

**One-line description:**

> A production-style financial-operations platform built with Java, Spring
> Boot, PostgreSQL, and Kafka to demonstrate transactional correctness,
> duplicate-safe distributed processing, immutable accounting, reconciliation,
> and tenant-scoped operations.

**Portfolio promise:** A reviewer can see not only what the system does, but
also which invariants it protects, where transaction boundaries sit, how
failure is recovered, and which executable evidence supports the claims.

## What the portfolio page contains

Publish the page in this order. Each section has one job and one primary piece
of evidence.

### 1. Hero

- Project name: **LedgerOps**
- Headline: **Engineering financial correctness under failure**
- One-line description from this guide
- Role: product definition, architecture, backend and frontend implementation,
  test design, and technical documentation
- Technology tags: Java 21, Spring Boot, Spring Modulith, PostgreSQL, Kafka,
  Keycloak, Redis, Next.js, Testcontainers
- Primary action: **Read the case study**
- Secondary action: **View the source repository**

The hero must identify LedgerOps as a simulation and learning project. It must
not imply real-money processing or regulatory certification.

### 2. Problem

Explain that the difficult part of a payment platform is not accepting a
request. The difficult part is preserving one defensible financial outcome
when requests race, messages repeat, external responses become ambiguous, and
settlement records conflict.

Use three framing questions:

- Can concurrent retries create only one logical Payment?
- Can a Payment become `COMPLETED` only with one matching balanced Ledger
  transaction?
- Can the system recover from provider and messaging failures without inventing
  a false result?

### 3. System overview

Show the major actors and trust boundaries:

- Operations Web and sandbox API clients
- LedgerOps Core modular monolith
- Provider Simulator
- PostgreSQL, Kafka, Redis, Keycloak, and MinIO
- Prometheus and Grafana operational views

Use the existing [system context diagram](../architecture/diagrams/release-0.3-context.md)
as the source. Keep the public diagram focused on relationships, not every
package or table.

### 4. Engineering decisions

Present five decisions as the core of the case study:

1. Enforce tenant-wide idempotency in PostgreSQL, including conflict detection
   for changed request content.
2. Commit Payment completion and its exact Ledger posting in one PostgreSQL
   transaction.
3. Use transactional outbox, consumer inbox, stable message identities, and
   idempotent consumers for at-least-once delivery.
4. Treat provider timeouts as ambiguous outcomes and recover through durable
   evidence and status queries instead of blind resubmission.
5. Preserve financial history through exact Reversal and controlled
   compensation rather than mutation or manual normalization.

### 5. End-to-end financial operations

Connect the decisions into one product journey:

```text
Payment request
  -> deterministic Risk decision
  -> durable Provider processing
  -> atomic Payment and Ledger completion
  -> settlement-file ingestion
  -> immutable Reconciliation run
  -> discrepancy and Case investigation
  -> controlled settlement posting or exact correction
```

Explain that Reconciliation owns reconciliation evidence, Ledger owns financial
postings, and Casework owns investigation and controlled resolution. No module
repairs another module by directly changing its tables.

### 6. Operations experience

Show how the Operations Web makes the backend evidence inspectable:

- Tenant- and Merchant-scoped administration
- Payment, Provider Attempt, Risk, and Ledger timelines
- full-payment Reversal
- settlement ingestion and Reconciliation
- discrepancy Cases and controlled corrections
- operational summaries, live activity, reports, and CSV exports

Use screenshots that reveal a decision or recovery path. Avoid a gallery of
generic dashboards.

### 7. Verification evidence

Use a compact evidence panel:

- 740 backend tests recorded at the Release 0.3 gate, with 0 failures, errors,
  or skipped tests
- clean V1–V45 Flyway installation and V14–V45 upgrade verification
- two bounded 100,000-record ingestion and Reconciliation tests
- contract fixtures, HMAC compatibility checks, and module-boundary tests
- 37 frontend tests and three Playwright runs covering 12 browser scenarios
- PostgreSQL, Kafka, Keycloak, Redis, MinIO, and Provider Simulator exercised in
  the recorded local topology evidence

These values describe the recorded Release 0.3 verification run. Update them
only after a later verified run.

### 8. Outcome and lessons

Close with what the project demonstrates:

- financial invariants belong in domain rules, transactions, and database
  constraints—not only service code;
- at-least-once delivery is safe only when identity, replay, and recovery are
  designed together;
- a modular monolith can preserve ownership while retaining valuable local
  transaction boundaries;
- operational evidence is part of the product because failures must be
  explainable to a human;
- explicit exclusions produce a more credible engineering story than broad,
  unsupported claims.

### 9. Repository links

End with a small set of useful paths:

- [Project README](../../README.md)
- [Release 0.1 transactional-core plan](../plans/release-0.1-transactional-core.md)
- [Release 0.2 distributed-processing plan](../plans/release-0.2-distributed-processing.md)
- [Release 0.3 financial-operations plan](../plans/release-0.3-financial-operations.md)
- [Architecture decisions](../adr/README.md)
- [Requirement traceability](../requirements/TRACEABILITY.md)
- [Release 0.3 demonstration scenarios](../demo/release-0.3-scenario-catalog.md)

## Package files

- [CASE_STUDY.md](CASE_STUDY.md) is the long-form portfolio narrative.
- [PROJECT_COPY.md](PROJECT_COPY.md) contains reusable copy for the hero,
  project card, resume, and interviews.
- [EVIDENCE_MAP.md](EVIDENCE_MAP.md) maps public claims to repository evidence
  and prevents accidental overstatement.

## Publication standard

The published page must remain understandable to a technical reviewer who has
not used LedgerOps. Define project-specific terms on first use, show why each
technology exists, and connect every major claim to an invariant, decision, or
test.

Do not publish secrets, real personal data, local credentials, internal host
details, or unsupported production/compliance claims. LedgerOps is a
production-style portfolio system and does not process real money.

