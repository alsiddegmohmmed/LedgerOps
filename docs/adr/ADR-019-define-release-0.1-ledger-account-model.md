# ADR-019: Define the Release 0.1 Ledger account model

Status: Accepted

Date: 2026-07-20

Decision owners: Product owner; Architecture owner

Supersedes: None

Superseded by: None

## Context

Product Definition v1.4 LED-02 required tenant-scoped Ledger accounts with a stable identifier, currency, status, and transaction history. Technical Specification v1.3 defined a six-code initial account catalog and strict double-entry posting, but neither document defined the account-status vocabulary, lifecycle, persistence identity, or posting eligibility rules.

Those omissions prevented a safe implementation of the Ledger account aggregate and persistence for Release 0.1 Slice 7. Choosing statuses, transitions, or uniqueness boundaries in code would have created unsupported product and architecture decisions. Account persistence therefore remained paused while the model was reviewed and approved. The existing immutable journal domain and its balance, tenant, currency, and compensation invariants remain unchanged.

## Decision

### Status and lifecycle

`LedgerAccountStatus` contains exactly `ACTIVE`. Every account is created `ACTIVE`, and Release 0.1 defines no status transition. Accounts cannot be suspended, closed, reactivated, archived, or deleted. Only `ACTIVE` accounts may be referenced by new Ledger entries.

Additional status values require a future Product Definition revision and ADR. Release 0.1 does not add `INACTIVE`, `SUSPENDED`, `CLOSED`, `ARCHIVED`, or another speculative state.

### Account-code catalog

The Release 0.1 catalog contains exactly:

- `CUSTOMER_RECEIVABLE`
- `MERCHANT_PAYABLE`
- `PROVIDER_CLEARING`
- `PLATFORM_FEE_REVENUE`
- `REVERSAL_PAYABLE`
- `SETTLEMENT_RECEIVABLE`

Release 0.1 does not permit another persisted account code.

### Account model and persistence identity

Each Ledger account contains `accountId`, `tenantId`, `accountCode`, `currency`, `status`, and `createdAt`. Every field is immutable. Status is also immutable because `ACTIVE` is the only permitted Release 0.1 value.

PostgreSQL enforces uniqueness equivalent to:

```sql
UNIQUE (tenant_id, account_code, currency)
```

The same code and currency may exist under different tenants. One tenant may have the same code for different currencies, but cannot have two accounts with the same code and currency.

Ledger owns its account, transaction, and entry records. Each entry references one account by `accountId` within the Ledger schema. No cross-schema foreign key is introduced.

### History and posting validation

`LedgerAccount` does not embed an unbounded collection of entries or transactions. Account transaction history is derived by querying immutable Ledger entries by `accountId`. Existing accounts, entries, balances, statements, and transaction history remain readable. Posted transactions and entries cannot be updated or deleted.

Before posting, Ledger verifies that every referenced account:

- exists;
- belongs to the Ledger transaction tenant;
- uses the Ledger transaction currency; and
- has status `ACTIVE`.

Account validation and posting occur in one short PostgreSQL transaction. A violation rejects and rolls back the complete posting, so no Ledger transaction or partial entry set survives.

### Release boundary

Release 0.1 includes the Ledger account domain model, persistence, approved account-code catalog, `ACTIVE` status, uniqueness and ownership constraints, posting lookup, queryable transaction history, and PostgreSQL/Testcontainers verification.

It excludes account-management UI, suspension, closure, reactivation, deletion, manual account administration, authorization workflows, status-change auditing, and additional account types.

## Consequences

Positive:

- Slice 7 has one deterministic account identity and posting-eligibility model.
- Tenant and currency isolation can be enforced in both domain logic and PostgreSQL.
- The closed status and code vocabularies prevent unsupported lifecycle and accounting concepts from entering Release 0.1.
- Account aggregates remain bounded while immutable entries provide complete queryable history.
- Failed account validation cannot leave a partial financial posting.

Negative or costly:

- Release 0.1 cannot temporarily disable or close an account.
- Adding an account status or code requires controlled product and architecture change.
- Database constraints and Testcontainers tests must cover identity immutability, non-deletion, catalog enforcement, and posting rollback in addition to domain validation.

## Alternatives considered

### Add future-looking account statuses

Rejected. `INACTIVE`, `SUSPENDED`, `CLOSED`, `ARCHIVED`, and related transitions have no approved Release 0.1 behavior, authorization, audit, balance, or posting semantics. Adding them would be speculative product design.

### Omit account status

Rejected. LED-02 explicitly requires status, and `ACTIVE` provides an explicit posting-eligibility invariant without inventing a lifecycle.

### Use account code without currency in the uniqueness boundary

Rejected. A tenant may maintain the same account role in multiple currencies. Excluding currency would contradict that supported model.

### Share accounts across tenants

Rejected. Ledger records are tenant-owned, and a shared account would weaken isolation and make posting ownership ambiguous.

### Embed all entries in LedgerAccount

Rejected. Transaction history is unbounded. Embedding it would create an ever-growing aggregate and couple account loading to the complete posting history.

### Allow account deletion when unused

Rejected. A deletion workflow adds lifecycle, race, audit, and historical-reference semantics that Release 0.1 does not define.

## Impact assessment

- Product requirements: Clarifies Product Definition LED-02 and the Release 0.1 Ledger scope without changing the existing double-entry or correction requirements.
- Data and migration: A future Ledger migration must enforce required fields, the exact status and code vocabularies, `UNIQUE (tenant_id, account_code, currency)`, immutable identity, non-deletion, and same-schema account-to-entry relationships.
- Security and tenancy: Every account is tenant-owned. Posting rejects an account from another tenant. Authorization and manual administration remain outside Release 0.1.
- Testing: Requires domain, migration, PostgreSQL/Testcontainers, atomic rollback, history-query, and Spring Modulith ownership evidence listed in Technical Specification v1.4.
- Operations and reliability: Invalid account references fail the complete posting. Existing immutable financial history remains readable.
- Cost and delivery: Adds only the minimum account model needed for Slice 7 and no account-management workflow or later-release infrastructure.
- Documentation: Product Definition v1.5, Technical Specification v1.4, the active plan, traceability, README, and AGENTS.md are aligned.

## Affected Product Definition sections

- Document control and revision history
- §7.6 Ledger and financial records
- LED-02 Ledger accounts
- §14 Product baseline decision

## Affected Technical Specification sections

- Document control and revision history
- §4.3 Module ownership
- §5.1 Primary aggregates
- §5.5 Ledger model
- §6.2 Data ownership rules
- §13.1 Test layers
- §13.2 Critical verification matrix
- §13.4 Release-blocking defects
- §14.1 Release 0.1 - Transactional Core
- §16 Architecture decision register
- §17.2 Correctness
- Implementation authorization

## Review conditions

Reconsider this decision through a Product Definition revision and a new or superseding ADR if LedgerOps needs another account status or code, account suspension or closure, manual account administration, deletion, status-change auditing, a different uniqueness boundary, or measured posting requirements that cannot be satisfied by the approved account lookup and one-transaction validation model.

## Approval

- Product owner: Approved
- Architecture owner: Approved
- Approved deviations recorded in authoritative documents: Product Definition v1.5 and Technical Specification v1.4
- Account-persistence status at approval: Paused pending reconciliation; implementation remains pending review of these controlled revisions
