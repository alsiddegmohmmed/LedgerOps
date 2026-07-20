# ADR-020: Define the Release 0.1 Payment-success posting

Status: Accepted

Date: 2026-07-21

Decision owners: Product owner; Architecture owner

Supersedes: None

Superseded by: None

## Summary

This ADR defines the accounting template, replay contract, module boundary, and transaction boundary implemented in Release 0.1 Slice 8. After confirmed provider `SUCCESS`, Ledger debits `PROVIDER_CLEARING` and credits `MERCHANT_PAYABLE` for the full Payment amount. The financial source identity is exactly `tenantId + PAYMENT + paymentId`.

The reconciled direction was reviewed and accepted on 21 July 2026. Slice 8 implementation is authorized against Product Definition v1.6 and Technical Specification v1.5.

## Context

Product Definition v1.5 §8.2 requires a Payment to become `COMPLETED` only when provider `SUCCESS` is confirmed and exactly one corresponding balanced Ledger transaction is posted atomically. Technical Specification v1.4 §§5.3, 5.6, and 9.3 repeat that invariant. ADR-016 preserves the lifecycle rule, and ADR-019 defines the Release 0.1 account-code catalog.

The documents did not define the exact Payment-success debit and credit accounts or the evidence required to validate a replay. They also mixed the Release 0.1 synchronous PostgreSQL boundary with provider evidence, inbox, outbox, and Kafka capabilities scheduled for Release 0.2. These gaps prevented a deterministic implementation.

## Decision

### Accounting template

Release 0.1 uses this exact Payment-success posting:

| Entry | Account code | Amount | Currency |
|---|---|---:|---|
| Debit | `PROVIDER_CLEARING` | Full Payment amount | Payment currency |
| Credit | `MERCHANT_PAYABLE` | Full Payment amount | Payment currency |

The posting contains exactly two entries. It does not post a fee, settlement movement, Reversal, correction, or partial amount.

`PROVIDER_CLEARING` represents value owed through the provider after confirmed `SUCCESS`. `MERCHANT_PAYABLE` represents the obligation owed to the merchant. `CUSTOMER_RECEIVABLE` is not used because provider `SUCCESS` means the customer-side obligation is no longer the outstanding receivable represented by this posting. `SETTLEMENT_RECEIVABLE` is not used because no settlement batch or settlement-file evidence exists at Payment completion. `PLATFORM_FEE_REVENUE` is not used because Release 0.1 defines no fee calculation.

This decision defines only the Payment-success template. It does not establish a general normal-balance calculation for every Ledger account.

### Financial source identity

The financial source identity is exactly:

```text
tenantId + PAYMENT + paymentId
```

PostgreSQL uniqueness remains `UNIQUE (tenant_id, source_type, source_id)`. For this template, `source_type` is `PAYMENT` and `source_id` is the Payment ID.

### State and posting matrix

| Payment state and posting evidence | Required result |
|---|---|
| `PROCESSING`; no `PAYMENT`-source posting exists | Perform the one atomic completion. |
| `PROCESSING`; a `PAYMENT`-source posting already exists | Raise a typed critical consistency error. Do not complete the Payment or create another posting. |
| `COMPLETED`; the exact expected posting exists | Return the existing logical result. Create no financial effect. |
| `COMPLETED`; no corresponding posting exists | Raise a typed critical consistency error. Do not create a late posting. |
| `COMPLETED`; a posting exists but differs in currency, amount, accounts, directions, entry count, source, or compensation state | Raise a typed critical consistency error. Do not normalize or replace the posting. |
| Any other Payment status | Raise a typed lifecycle error. Create no Ledger record. |

### Exact replay verification

The existing posting is a valid replay only when all these conditions hold:

- it has the same `tenantId`;
- `sourceType` is `PAYMENT`;
- `sourceId` equals `paymentId`;
- its currency equals the Payment currency;
- total debits equal the full Payment amount;
- total credits equal the full Payment amount;
- it contains exactly two entries;
- one entry is a `DEBIT` to `PROVIDER_CLEARING`;
- one entry is a `CREDIT` to `MERCHANT_PAYABLE`; and
- it has no compensation reference.

A source record's existence alone is insufficient.

### Module boundary

Payment owns completion orchestration and Payment lifecycle persistence. Ledger owns account lookup, template construction, Ledger validation, posting persistence, and source-based posting lookup.

Payment calls only a published `ledger::api` boundary. Ledger exposes a focused operation that either finds an existing posting by tenant and `PAYMENT` source identity or atomically posts or returns the existing exact Payment-success posting. The published API exposes neutral contract values and does not expose Ledger domain, application, infrastructure, or persistence types.

Payment does not query Ledger tables. Ledger does not query or mutate Payment tables.

### Transaction boundary

Payment begins and owns one short PostgreSQL transaction. The Ledger operation joins that transaction and must not use `REQUIRES_NEW` or an independent transaction.

The completion use case performs this sequence:

1. Lock the tenant-scoped Payment row.
2. Validate the Payment and existing-posting state against the matrix.
3. Resolve the tenant's ACTIVE `PROVIDER_CLEARING` and `MERCHANT_PAYABLE` accounts for the Payment currency.
4. Persist the exact Ledger transaction and two entries.
5. Apply `Payment.complete()`.
6. Update Payment using its expected persistence version.
7. Commit once.

Any failure rolls back the Payment update, Ledger transaction, and all Ledger entries. If either required account is absent, cross-tenant, wrong-currency, wrong-code, or not ACTIVE, Ledger raises a typed configuration or posting error, persists nothing, and leaves Payment `PROCESSING`.

Concurrent completion requests serialize on the tenant-scoped Payment row. Exactly one request performs the transition and posting. Later requests validate and return the exact existing result. Payment versioning and Ledger source uniqueness remain database-enforced safeguards.

### Provider-success boundary

Release 0.1 exposes only an internal use case with a clear contract such as `CompletePaymentAfterProviderSuccess`. Its invocation represents an already-confirmed provider `SUCCESS`; it does not accept a generic boolean and it is not exposed as a public endpoint that permits arbitrary Payment success.

Release 0.1 proves only the atomic Payment-to-Ledger boundary. It does not claim durable provider evidence, provider-attempt persistence, provider-result deduplication, callback handling, inbox, outbox, or Kafka delivery. Release 0.2 adds those capabilities around this boundary without weakening its atomicity or replay identity.

## Consequences

Positive:

- Slice 8 has one deterministic accounting template and one exact replay contract.
- Provider-confirmed value and the merchant obligation are represented without a customer receivable, settlement receivable, or unsupported fee.
- Payment completion and its financial effect cannot diverge within the successful PostgreSQL transaction.
- Duplicate and concurrent invocations create one Payment transition and one Ledger posting.
- Cross-module ownership remains explicit and enforceable.

Negative or costly:

- Release 0.1 trusts the internal invocation to represent already-confirmed provider `SUCCESS`; it does not persist durable provider evidence.
- Existing inconsistent state becomes a typed critical failure requiring investigation, not an automatic repair.
- Replay requires loading and validating the complete posting shape, not only its source identity.
- Release 0.2 must add provider and delivery evidence without weakening this boundary.

## Alternatives considered

### Debit `CUSTOMER_RECEIVABLE`

Rejected. This account represents a direct customer-side obligation. After provider `SUCCESS`, the posting recognizes value owed through the provider instead.

### Debit `SETTLEMENT_RECEIVABLE`

Rejected. Payment completion has no settlement batch or settlement-file evidence.

### Post `PLATFORM_FEE_REVENUE`

Rejected. Release 0.1 defines no fee calculation.

### Let Payment select arbitrary Ledger accounts

Rejected. The accounting template belongs to Ledger. Caller-selected accounts would weaken ownership and permit inconsistent financial effects.

### Treat source existence as sufficient replay evidence

Rejected. A posting with the right source but the wrong amount, currency, accounts, directions, entry count, or compensation state is a critical inconsistency.

### Create or replace a posting when replay finds inconsistent state

Rejected. Late repair or normalization would conceal the failure of the original atomicity invariant.

### Use an independent or `REQUIRES_NEW` Ledger transaction

Rejected. Separate commits can persist only one side of the Payment-to-Ledger boundary.

### Add provider evidence, inbox, outbox, and Kafka in Release 0.1

Rejected for Release 0.1. The approved roadmap introduces distributed provider processing and delivery evidence in Release 0.2.

## Impact assessment

- Product requirements: Clarifies PAY-03, LED-01, LED-03, BR-03, BR-05, and the §8.2 meaning of `COMPLETED`. It does not add a Payment state or transition.
- Data and migration: Uses the existing Payment version and Ledger V6/V7 schema. No migration is required. Source uniqueness remains `(tenant_id, source_type, source_id)`.
- Security and tenancy: Payment locking, source lookup, and Ledger account resolution are tenant-scoped.
- Module ownership: Payment owns orchestration and lifecycle persistence. Ledger owns lookup, template construction, validation, posting persistence, and source lookup. Communication uses only `ledger::api`.
- Testing: Requires the exact template; full amount and currency; missing-account, Ledger-insert, and Payment-update rollback; every state/posting matrix case; valid replay; duplicate-source and concurrent-completion behavior; tenant isolation; module boundaries; absence of a public completion endpoint; one joined transaction; and absence of Release 0.2 infrastructure.
- Operations: Typed critical consistency errors identify impossible or historically inconsistent Payment/posting combinations. Logs preserve correlation, tenant, Payment, and Ledger identifiers without sensitive payloads.
- Cost and delivery: Uses the modular monolith and PostgreSQL transaction manager without new infrastructure.

## Affected Product Definition sections

- Document control and revision history
- §7.3 Payment intake and lifecycle, PAY-03
- §7.6 Ledger and financial records, LED-01 and LED-03
- §8.1 Global business rules, BR-03 and BR-05
- §8.2 Payment lifecycle
- §14 Product baseline decision

## Affected Technical Specification sections

- Document control and revision history
- §4.3 Module ownership
- §5.1 Primary aggregates
- §5.3 Payment state machine
- §5.5 Ledger model
- §5.6 Atomic Payment completion
- §5.7 Idempotency and concurrency
- §6.2 Data ownership rules
- §9.2 Submission workflow
- §9.3 Provider result categories
- §13.1 Test layers
- §13.2 Critical verification matrix
- §13.4 Release-blocking defects
- §14.1 Release 0.1 - Transactional Core
- §14.2 Release 0.2 - Distributed Processing
- §16 Architecture decision register
- §17.2 Correctness
- Implementation authorization

## Required verification

- The posting has exactly two entries: full-amount `DEBIT` to `PROVIDER_CLEARING` and full-amount `CREDIT` to `MERCHANT_PAYABLE` in the Payment currency.
- Missing account validation rolls back every effect and leaves Payment `PROCESSING`.
- Ledger insertion failure rolls back every effect and leaves Payment `PROCESSING`.
- Payment update failure rolls back the Ledger transaction and entries.
- `PROCESSING` with a pre-existing posting raises a typed critical consistency error.
- `COMPLETED` without a posting raises a typed critical consistency error and creates no late posting.
- A mismatched existing posting raises a typed critical consistency error and is not normalized or replaced.
- Valid replay returns the original result without a new financial effect.
- Database source uniqueness prevents duplicate postings.
- Concurrent completion creates one transition and one posting.
- Tenant isolation applies to Payment locking, source lookup, and account resolution.
- No public completion endpoint exists.
- Spring Modulith permits Payment to depend only on `ledger::api` and prohibits reverse or persistence coupling.
- Ledger joins the Payment-owned transaction; no `REQUIRES_NEW` or independent transaction exists.
- Release 0.1 adds no provider-attempt, provider-result, callback, inbox, outbox, or Kafka infrastructure.

## Review conditions

Reconsider this decision through a Product Definition revision and a new or superseding ADR if LedgerOps changes when customer, provider-clearing, merchant-payable, fee, or settlement effects are recognized; supports partial capture or multi-stage financial recognition; permits multiple postings for one Payment source; or introduces a provider contract that cannot preserve the same PostgreSQL atomicity and replay identity.

## Approval

- Product owner: Approved, 21 July 2026
- Architecture owner: Approved, 21 July 2026
- ADR status: Accepted
- Slice 8 implementation status: Completed; the required domain, PostgreSQL, rollback, replay, concurrency, HTTP-boundary, and Modulith evidence passes
