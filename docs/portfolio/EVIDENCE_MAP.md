# LedgerOps portfolio evidence map

This map is the factual control for the LedgerOps portfolio package. Use it to
verify public claims and to find supporting material during interviews. It is
not intended to appear verbatim on the portfolio page.

## Claim rules

- Say **production-style**, not production-proven or production-grade.
- Say **at-least-once delivery with idempotent effects**, not end-to-end exactly
  once.
- Say **simulation** or **sandbox**, not payment processor or regulated system.
- Describe the recorded test results as release-gate evidence, not continuous
  production performance.
- Do not claim real-money processing, regulatory certification, real provider
  integrations, public-cloud operation, or arbitrary financial correction.

## Public claim map

### Product purpose and scope

**Claim:** LedgerOps is a production-style, multi-tenant transaction-processing
and financial-operations platform focused on correctness.

**Evidence:**

- [Project README](../../README.md)
- Product Definition v1.7, §§3–4 and Appendix A
- [Release 0.3 financial-operations plan](../plans/release-0.3-financial-operations.md)

**Qualification:** It is a simulation and learning project. It does not process
real money or store real payment credentials.

### Modular architecture

**Claim:** The Core Platform is a Spring Boot modular monolith with explicit
module and data ownership.

**Evidence:**

- [Project README architecture](../../README.md#architecture)
- [ADR-025 module and messaging contracts](../adr/ADR-025-define-release-0.3-module-and-messaging-contracts.md)
- Spring Modulith verification and ArchUnit tests under `src/test/java`
- Module packages under `src/main/java/com/ledgerops`

**Qualification:** Reconciliation remains inside the modular monolith. Its
possible extraction is a later, evidence-driven decision.

### Payment idempotency

**Claim:** Concurrent equivalent requests under one Tenant and idempotency key
converge on one logical Payment; materially different reuse is rejected.

**Evidence:**

- [Release 0.1 plan](../plans/release-0.1-transactional-core.md), Payment
  creation and idempotency evidence
- [ADR-017 tenant-wide Payment API idempotency](../adr/ADR-017-use-tenant-wide-payment-api-idempotency.md)
- PostgreSQL tests under `src/test/java/com/ledgerops/payment`

**Mechanism:** PostgreSQL arbitrates `(tenant_id, idempotency_key)`. A canonical
request fingerprint distinguishes an equivalent replay from a conflict,
including a changed Merchant.

### Atomic Payment and Ledger completion

**Claim:** A Payment becomes `COMPLETED` only when its exact balanced Ledger
posting commits in the same PostgreSQL transaction.

**Evidence:**

- [ADR-020 Payment-success posting](../adr/ADR-020-define-release-0.1-payment-success-posting.md)
- [Release 0.1 plan](../plans/release-0.1-transactional-core.md), Slice 8
- Payment/Ledger PostgreSQL integration and failpoint tests

**Exact template:** Debit `PROVIDER_CLEARING`, credit `MERCHANT_PAYABLE`, full
Payment amount and currency, source identity `tenantId + PAYMENT + paymentId`.

**Qualification:** Completion means confirmed Provider success plus the atomic
Ledger effect. It does not mean settlement reconciliation is complete.

### Duplicate-safe distributed processing

**Claim:** LedgerOps uses Kafka at-least-once delivery while preventing duplicate
business and financial effects.

**Evidence:**

- [ADR-021 Provider and messaging semantics](../adr/ADR-021-define-release-0.2-provider-and-messaging-semantics.md)
- [Release 0.2 plan](../plans/release-0.2-distributed-processing.md)
- Messaging, Provider, and Payment tests under `src/test/java/com/ledgerops`

**Mechanism:** Transactional outbox records, consumer inbox records, stable
message and business identities, database uniqueness, immutable Provider
evidence, fenced workers, and exact replay validation.

**Qualification:** Kafka delivery can occur more than once. The system makes the
resulting effects idempotent; it does not claim end-to-end exactly-once
delivery.

### Provider ambiguity and recovery

**Claim:** A Provider timeout is treated as an unknown outcome rather than an
automatic Payment failure.

**Evidence:**

- [ADR-021 Provider and messaging semantics](../adr/ADR-021-define-release-0.2-provider-and-messaging-semantics.md)
- [Provider flow diagram](../architecture/diagrams/release-0.2-provider-flow.md)
- [Release 0.2 operations runbook](../runbooks/release-0.2-operations.md)

**Mechanism:** LedgerOps preserves the attempt and interaction evidence, queries
Provider status using a stable idempotency key, bounds recovery, and permits
resubmission only with authoritative safe evidence.

### Reversal correctness

**Claim:** LedgerOps permits one full Reversal per eligible Payment and
compensates the original Payment posting exactly.

**Evidence:**

- [ADR-023 Reversal semantics](../adr/ADR-023-define-release-0.3-reversal-provider-and-accounting-semantics.md)
- [Release 0.3 Reversal flow](../architecture/diagrams/release-0.3-reversal-flow.md)
- [Release 0.3 Slice 6](../plans/release-0.3/slice-06-full-payment-reversal.md)

**Exact template:** Debit `MERCHANT_PAYABLE`, credit `PROVIDER_CLEARING`, full
Payment amount and currency, with a compensation link to the original posting.

**Qualification:** Partial Reversals are not part of the implemented model.

### Settlement ingestion, Reconciliation, and correction

**Claim:** Settlement ingestion and Reconciliation preserve immutable evidence,
produce deterministic results, and prevent reruns from duplicating financial
effects.

**Evidence:**

- [ADR-024 settlement and correction semantics](../adr/ADR-024-define-release-0.3-settlement-posting-and-controlled-correction-semantics.md)
- [Settlement-file contract](../api/provider-settlement-file-v1.md)
- [Reconciliation and correction flow](../architecture/diagrams/release-0.3-reconciliation-correction-flow.md)
- [Release 0.3 Slices 7–9](../plans/release-0.3-financial-operations.md)

**Mechanism:** Content-addressed raw files, canonical record versions,
occurrence evidence, immutable runs and results, a separately controlled current
run, stable `SettlementPostingInstruction` identity, and exact replay.

**Qualification:** Corrections can compensate only an invalidated,
uncompensated `SETTLEMENT_ADJUSTMENT`. Users cannot select arbitrary accounts or
rewrite Payment/Reversal postings.

### Authentication, authorization, and tenancy

**Claim:** Keycloak authenticates, while Core PostgreSQL authorizes each
protected request using current Tenant, membership, role, permission, and
Merchant-scope data.

**Evidence:**

- [ADR-022 identity, tenancy, and authorization](../adr/ADR-022-define-release-0.3-identity-tenancy-and-authorization.md)
- [Release 0.3 security model](../security/release-0.3-security-model.md)
- [Role-permission matrix](../security/release-0.3-role-permission-matrix.md)
- Protected-path and authorization tests under `src/test/java`

**Qualification:** Redis holds ephemeral browser session state. It is not the
authorization source of truth.

### Operations Web

**Claim:** The Next.js Operations Web exposes the principal administrative and
financial-operations workflows through a backend-for-frontend session model.

**Evidence:**

- [Release 0.3 Slice 10](../plans/release-0.3/slice-10-operational-experience.md)
- Application under `applications/operations-web`
- Frontend unit and Playwright tests

**Qualification:** Public copy must not claim Arabic/RTL parity or completed
manual accessibility review. Those items are not part of the recorded evidence.

### Verification depth

**Claim:** The recorded Release 0.3 gate passed 740 backend tests and exercised
large-batch, migration, contract, frontend, browser, and local-topology evidence.

**Evidence:**

- [Release 0.3 Slice 11 gate](../plans/release-0.3/slice-11-release-gate.md)

**Recorded results:**

- `./gradlew clean test`: 740 tests, 0 failures, 0 errors, 0 skipped
- `./gradlew check`: passed
- `./gradlew releaseScaleTest`: two bounded 100,000-record tests passed
- Flyway: fresh V1–V45 installation and V14–V45 upgrade with Tenant preservation
- frontend: lint, type checking, 37 tests, build, and three Playwright runs
  covering 10 + 1 + 1 scenarios
- contracts: JSON Schema/HMAC fixtures and Provider Simulator compatibility
- dependency/secret checks: production dependency audit and tracked
  high-confidence secret scan passed

**Qualification:** This is recorded repository evidence from the Release 0.3
gate. The portfolio must not reinterpret it as a public-cloud availability,
security-certification, or production-throughput claim.

## Interview evidence trail

For a technical interview, explain each decision in this order:

1. State the failure or inconsistency being prevented.
2. Identify the owner and consistency boundary.
3. Explain the stable identity and database constraint.
4. Describe replay, concurrency, and rollback behavior.
5. Point to the focused test or release-gate evidence.

This structure keeps the discussion about engineering reasoning rather than
framework vocabulary.
