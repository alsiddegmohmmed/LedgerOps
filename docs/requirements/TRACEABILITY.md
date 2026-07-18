# Requirement traceability — Release 0.1 starter

Status values: `Implemented`, `Partial`, `Planned`, `Deferred`, or `Blocked`.

This matrix tracks only requirements touched by Release 0.1. Product requirements not listed here remain part of the authoritative Product Definition and are not silently removed.

| Requirement | Release 0.1 interpretation | Planned or current evidence | Status |
|---|---|---|---|
| TEN-01 | tenant identity and lifecycle basics; full initial-admin/auth flow remains later work | `Tenant` lifecycle tests, Flyway schema, persistence integration tests | Partial |
| PAY-01 | create a stable tenant/merchant-owned payment with amount, currency, customer, reference, and idempotency key | payment domain/API and PostgreSQL integration tests | Planned |
| PAY-02 | equivalent repeats return the original result; conflicting payload returns an idempotency conflict | tenant/merchant/key uniqueness, request fingerprint, sequential and concurrent Testcontainers tests | Planned |
| PAY-03 | only approved payment state transitions may occur | domain transition tests including invalid paths | Planned |
| RSK-01 | evaluate every eligible payment using synchronous configured rules | deterministic rule evaluation and recorded outcomes | Planned |
| RSK-02 | return approve, manual review, or reject | threshold and decision tests; manual queue is outside this release | Planned |
| LED-01 | balanced journal transaction with at least one debit and credit | domain/property tests plus PostgreSQL constraint/integration evidence | Planned |
| LED-03 | ledger posting links to the originating payment | bidirectional identifiers and integration test | Planned |
| LED-04 | posted financial history is immutable | update/delete prevention and compensating-reference tests | Planned |
| BR-01 | every business-owned record belongs to one tenant | non-null `tenant_id`, scoped repositories, isolation tests for each tenant-owned module | Partial |
| BR-02 | money has explicit currency and correct decimal handling | Money value-object tests; no floating point | Planned |
| BR-03 | correct financial history using new records | immutable ledger design and compensation tests | Planned |
| BR-04 | one tenant and idempotency key identifies one logical payment | database uniqueness and concurrency evidence | Planned |
| BR-10 | suspended tenants retain history but cannot create activity | `Tenant.canCreateOperationalActivity()` exists; application/API enforcement and read test remain | Partial |
| BR-12 | time is consistent and displayed with locale/timezone context | UTC `Clock` bean exists; broader persistence/API evidence remains | Partial |

## Technical decision evidence

| Technical section | Decision | Evidence/status |
|---|---|---|
| §4.1–4.4 | modular monolith, internal layering, owned schemas, no cross-table access | tenancy module declaration and Modulith verification exist; expand with each module |
| §5.2 | explicit value objects and injected time | `TenantId` and `Clock` exist; Money and other IDs planned |
| §5.5 | strict, single-currency double-entry ledger | Planned in ledger slice |
| §5.6 | atomic payment completion and ledger posting | Planned in atomic completion slice |
| §5.7 | database idempotency, narrow locking, injected `Clock` | `Clock` exists; idempotency and concurrency evidence planned |
| §6.1–6.4 | one PostgreSQL database, module schemas, Flyway ownership | tenancy schema and Testcontainers migration test implemented |
| §13 | layered tests and critical verification | domain, PostgreSQL, and Modulith foundations implemented; critical workflow tests planned |
| §14.1 | Release 0.1 scope and exit gate | tracked in `docs/plans/release-0.1-transactional-core.md` |

## Update rule

A requirement becomes `Implemented` only when its complete acceptance behaviour has executable evidence. A class or happy-path test alone is not enough. Every implementation pull request must update this matrix or explain why no mapped requirement changed.
