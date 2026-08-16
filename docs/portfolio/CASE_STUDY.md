# LedgerOps: Engineering financial correctness under failure

LedgerOps is a production-style, multi-tenant financial-operations platform I
built to explore a harder question than how to create a Payment API:

> How should a financial backend behave when requests race, messages repeat,
> providers time out, workers crash, and settlement evidence conflicts with
> internal state?

The result is a Java and Spring Boot system that connects Payment intake,
deterministic Risk evaluation, durable Provider processing, immutable
double-entry accounting, full-payment Reversal, settlement ingestion,
Reconciliation, Case investigation, controlled correction, and an operational
web application.

LedgerOps is a simulation and learning project. It does not process real money,
store real card or bank credentials, or claim regulatory certification. Its
purpose is to make financial-system reasoning visible through code, database
constraints, architecture decisions, failure behavior, and executable tests.

## Project snapshot

**My role:** Product definition, domain and system architecture, backend and
frontend implementation, test design, release planning, and technical
documentation.

**Core technologies:** Java 21, Spring Boot 4, Spring Modulith, PostgreSQL,
Flyway, Apache Kafka, Testcontainers, Keycloak, Redis, Spring Batch, MinIO,
Next.js, React, OpenTelemetry, Prometheus, and Grafana.

**System shape:** A modular monolith for the Core Platform, a separate Provider
Simulator, a Next.js Operations Web application, and supporting infrastructure
for messaging, identity, session state, object storage, and telemetry.

**Recorded verification:** The final Release 0.3 gate recorded 740 passing
backend tests, V1–V45 clean-install and upgrade evidence, two bounded
100,000-record ingestion and Reconciliation tests, 37 frontend tests, and three
Playwright runs covering 12 browser scenarios.

## The problem was consistency, not CRUD

A conventional Payment demo can accept a request, save a row, and return a
status. That path says little about what happens after the network becomes
unreliable or two actors perform the same action concurrently.

LedgerOps starts from the failures that can invalidate a financial system:

- Two concurrent requests use the same idempotency key.
- A message is published or consumed more than once.
- A Provider accepts a request but the HTTP response never reaches Core.
- A callback arrives late, twice, or out of order.
- A Payment is marked complete but its accounting entry fails.
- A settlement file contains duplicates, corrected content, or evidence that
  disagrees with the internal record.
- An operator attempts a financial correction while another run is being
  promoted or posted.
- A user's membership or credential is revoked while an authenticated session
  still exists.

I treated those cases as primary design inputs. The happy path then became one
valid result of a system designed around explicit identities, ownership,
transactions, replay, and recovery.

## The invariants shaped the architecture

Before selecting infrastructure, I defined the properties the system could not
violate:

1. A completed Payment has exactly one matching balanced Ledger transaction.
2. Equivalent retries return one logical result; conflicting reuse is rejected.
3. Every financial posting balances by currency and remains immutable.
4. Reversal and correction use compensating entries instead of editing history.
5. Duplicate delivery can repeat processing but cannot repeat a business or
   financial effect.
6. A timeout is not evidence that a Provider rejected or ignored a request.
7. Tenant and Merchant context applies to every protected action.
8. Each module owns its data and exposes focused application contracts rather
   than allowing cross-module table access.

Those invariants explain the technology choices. PostgreSQL owns transactional
truth and uniqueness. Kafka provides at-least-once delivery, not an exactly-once
promise. The transactional outbox connects database state to later publication.
Spring Modulith and architecture tests enforce ownership inside the monolith.
Keycloak authenticates, while Core PostgreSQL makes current authorization
decisions. Testcontainers validates behavior against real infrastructure rather
than an in-memory database substitute.

## Architecture: modular where ownership matters, local where atomicity matters

The Core Platform is a Spring Boot modular monolith. Modules include Tenancy,
Merchant, Customer, Identity, Payment, Risk, Provider, Messaging, Ledger,
Reconciliation, Casework, Notification, Audit, Reporting, and Administration.
Each module owns its schema and publishes narrow contracts for other modules.

This structure was deliberate. Payment completion needs one short PostgreSQL
transaction that spans a Payment transition and a Ledger posting. Keeping that
consistency boundary local avoids introducing a distributed transaction for a
problem PostgreSQL can solve directly. At the same time, module boundaries stop
Payment from querying Ledger tables and stop Ledger from mutating Payment state.

External work uses different boundaries. Provider HTTP calls occur outside
database transactions. Kafka delivery is asynchronous. Browser sessions are
ephemeral. Settlement files live in object storage with immutable metadata in
PostgreSQL. The design uses local atomicity where it is valuable and durable
handoffs where a network boundary makes local atomicity impossible.

## Decision 1: make idempotency a database-enforced business contract

Payment idempotency is Tenant-wide. The pair `tenantId + idempotencyKey`
identifies one logical request even when two requests arrive concurrently or
use different Merchants inside the same Tenant.

For each request, LedgerOps computes a canonical fingerprint from the Merchant,
Customer, normalized amount, currency, and payment-method category. PostgreSQL
arbitrates the unique Tenant/idempotency-key pair:

- If the stored fingerprint matches, LedgerOps returns the original Payment.
- If the fingerprint differs, LedgerOps reports an explicit idempotency
  conflict.
- If two equivalent requests race, the database converges them on one logical
  Payment.

This is stronger than checking for an existing key before inserting. A
read-then-write implementation leaves a race between the read and insert. The
database constraint is the final arbiter, while the fingerprint preserves the
business meaning of a replay.

## Decision 2: complete the Payment and post the Ledger entry together

Provider success alone does not make a Payment `COMPLETED`. LedgerOps requires
the Payment state change and its exact accounting effect to commit together.

The Payment-success posting is fixed:

```text
DEBIT  PROVIDER_CLEARING
CREDIT MERCHANT_PAYABLE
amount and currency = the full Payment amount and currency
source identity     = tenantId + PAYMENT + paymentId
```

Payment owns the completion use case. Ledger owns account resolution, posting
validation, persistence, and exact replay verification. The operation locks the
Tenant-scoped Payment, validates existing posting evidence, creates the two
Ledger entries, changes the Payment state, and commits once.

If account configuration, Ledger persistence, or the Payment update fails, the
entire transaction rolls back. A retry succeeds only when an existing posting
matches the expected amount, currency, accounts, directions, entry count,
source, and compensation state. Finding a row with the right source identifier
is not sufficient if its contents are wrong.

This decision turns an architectural claim into an enforceable consistency
boundary: the database cannot commit a successful Payment without its matching
financial history through the approved application path.

## Decision 3: design at-least-once delivery around identity and recovery

Kafka can redeliver. A publisher can crash after Kafka accepts a message but
before Core records the acknowledgement. A consumer can complete its work and
crash before acknowledging the message. LedgerOps therefore assumes repeated
delivery instead of treating it as an exceptional edge case.

Business transactions append stable messages to a transactional outbox. A
publisher claims and delivers those records later. Consumers record inbox
identity and business effects atomically. Provider work uses durable records,
leases, immutable interaction evidence, and stable Provider idempotency keys.
Database uniqueness protects the final business and financial identities.

The important guarantee is not that every message appears once. The guarantee
is that repeated delivery converges on one valid effect or exposes a typed
inconsistency for investigation.

This became especially important for Provider timeouts. A timeout means the
outcome is unknown: the Provider might have accepted the request even though
Core did not receive the response. LedgerOps preserves the attempt, queries
status using the stable Provider identity, bounds the recovery process, and
never blindly resubmits without authoritative safe-to-resubmit evidence.

## Decision 4: preserve financial history through constrained compensation

Ledger entries are append-only. LedgerOps does not repair history by changing a
posted transaction.

A full Payment Reversal is a separate workflow with its own Provider attempts
and evidence. Only one Reversal is permitted per eligible Payment, and it uses
the exact inverse of the original Payment posting:

```text
DEBIT  MERCHANT_PAYABLE
CREDIT PROVIDER_CLEARING
amount and currency = the full original Payment amount and currency
compensates         = the original Payment Ledger transaction
```

Settlement correction is even narrower. A Case may authorize compensation only
for an invalidated, uncompensated `SETTLEMENT_ADJUSTMENT`. The user does not
choose accounts or directions. Ledger posts the exact inverse and records the
original transaction being compensated. Payment and Reversal postings cannot
be manually normalized through this mechanism.

The constraint is intentional. A generic journal-administration screen would
be easier to demonstrate, but it would make the financial model less
defensible. LedgerOps exposes only correction paths whose source evidence,
identity, authorization, and accounting template are defined.

## Decision 5: make settlement disagreement explicit and reproducible

Settlement files are external evidence, not instructions to force internal
records to agree.

LedgerOps stores raw file bytes immutably, validates the file in a streaming
pipeline, preserves record-level errors, and distinguishes an exact duplicate
from corrected content. Canonical record versions represent normalized content;
separate occurrence evidence preserves each physical row, including duplicates
and conflicts.

Reconciliation runs capture immutable inputs and results. A separate pointer
identifies the current run for a batch family. Promotion, settlement posting,
and correction serialize on the same batch-family control so competing
operations cannot make incompatible decisions.

Matching is deterministic. A mismatch becomes a named discrepancy and, when
required, a Case. LedgerOps does not silently alter a Payment, create a missing
Payment, or rewrite a Ledger posting to make the records agree. That design
makes reruns explainable and keeps the correction boundary auditable.

## Authorization remains current even when authentication is old

Keycloak authenticates users and services, but it does not decide current
business authorization. Core PostgreSQL owns application users, memberships,
roles, permissions, Merchant scopes, credentials, support sessions, and audit
evidence.

Human requests carry an explicit Tenant selection. The Core Platform validates
that context against current PostgreSQL data on every protected request.
Service requests derive their Tenant and Merchant from one active credential.
Revocation therefore takes effect from transactional truth rather than waiting
for a browser session or token to expire.

The Operations Web uses a Next.js backend-for-frontend with Authorization Code
and PKCE. Tokens stay server-side; the browser receives an opaque Secure
HttpOnly session cookie. Redis stores ephemeral session state, but it is never
the source of authorization truth.

## Turning backend evidence into an operational product

The Operations Web is not a decorative dashboard over a Payment table. It
exposes the evidence needed to understand and act on the system:

- Payment state, Risk decision, Provider Attempts, operational events, and
  Ledger postings;
- Tenant, Merchant, membership, credential, and support administration;
- manual Risk and Provider controls with permission and audit boundaries;
- full-payment Reversal and safe retry paths;
- settlement upload, validation, Reconciliation, discrepancies, and Cases;
- controlled settlement correction;
- operational summaries, live activity, reports, and audited CSV exports.

Reporting and live views are rebuildable projections. They improve search and
operational experience but never replace Payment, Ledger, Provider, or
Reconciliation transactional truth.

## Verification focused on failure, replay, and concurrency

The project uses layered verification: plain domain tests for state machines and
value objects, PostgreSQL Testcontainers for constraints and transactions,
Kafka and Provider integration tests for repeated delivery and recovery,
contract fixtures for JSON and HMAC bytes, ArchUnit and Spring Modulith checks
for architecture, and frontend and Playwright tests for operational workflows.

The recorded Release 0.3 gate reported:

- 740 backend tests with 0 failures, errors, or skipped tests;
- successful `check` and focused contract verification;
- clean Flyway V1–V45 installation and V14–V45 upgrade with Tenant preservation;
- two bounded 100,000-record settlement-ingestion and Reconciliation tests;
- 37 frontend tests plus successful lint, type checking, and build;
- three Playwright runs covering 12 browser scenarios;
- local topology evidence across PostgreSQL, Kafka, Redis, Keycloak, MinIO,
  Prometheus, Grafana, and the Provider Simulator databases.

The number of tests is not the main result. The important result is what they
try to disprove: duplicate financial effects, invalid state transitions,
cross-Tenant access, partial commits, unsafe retry, migration loss, nondeterministic
reruns, and unauthorized correction.

## Tradeoffs I chose deliberately

**Modular monolith over premature services.** The architecture preserves clear
ownership without giving up the joined PostgreSQL transaction required for
Payment and Ledger completion.

**At-least-once delivery over an exactly-once claim.** Stable identity,
idempotent effects, and replay validation describe the real failure model more
honestly than a transport-level slogan.

**Deterministic Reconciliation over fuzzy matching.** Financial discrepancies
must be explainable. Ambiguous matches become investigation work rather than
automatic truth.

**Constrained corrections over generic journal editing.** A smaller, evidence-
bound operation protects the accounting model and audit trail.

**A Simulator over real integrations.** The Provider Simulator makes timeouts,
duplicates, invalid callbacks, status recovery, and settlement mismatches
repeatable without handling real payment data.

## Outcome

LedgerOps demonstrates a complete chain of financial reasoning: accept a
request once, evaluate Risk reproducibly, communicate across an unreliable
boundary, record one accounting result, reconcile that result with external
evidence, and correct only through controlled compensation.

The project strengthened how I reason about consistency boundaries. Database
constraints, domain rules, message identity, transaction ownership, and
operational evidence cannot be designed independently. They have to agree on
what one logical action means before the system can recover safely.

That is the central result of LedgerOps: not a claim that failures disappear,
but a system that makes failures bounded, visible, replayable, and financially
safe.

## Explore the evidence

- [Project README](../../README.md)
- [Release 0.1: Transactional Core](../plans/release-0.1-transactional-core.md)
- [Release 0.2: Distributed Processing](../plans/release-0.2-distributed-processing.md)
- [Release 0.3: Identity and Financial Operations](../plans/release-0.3-financial-operations.md)
- [Architecture decision records](../adr/README.md)
- [Requirement traceability](../requirements/TRACEABILITY.md)
- [Portfolio evidence map](EVIDENCE_MAP.md)

