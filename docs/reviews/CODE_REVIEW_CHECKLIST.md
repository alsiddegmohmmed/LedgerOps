# LedgerOps code review checklist

Review correctness before style. Mark an item `N/A` only with a short reason.

## Blockers

- [ ] A completed payment cannot exist without exactly one posted ledger transaction.
- [ ] Every posted ledger transaction has debit and credit entries and balances by currency.
- [ ] Posted entries cannot be edited or deleted; corrections are compensating records.
- [ ] Duplicate or concurrent requests cannot create duplicate business or financial effects.
- [ ] Tenant-owned reads and writes cannot cross tenant boundaries.
- [ ] Transaction boundaries prevent partial financial state and avoid long external calls.
- [ ] Migrations are recoverable, deterministic, and do not modify a released migration.
- [ ] Authorization cannot be bypassed for high-risk actions.

## Domain and architecture

- [ ] Behaviour matches mapped product requirements and technical decisions.
- [ ] Domain code is free of Spring, JPA, HTTP, and infrastructure dependencies.
- [ ] Business rules live in cohesive domain/application code, not controllers or JPA entities.
- [ ] Module ownership is respected; there is no cross-module table access.
- [ ] Value objects make invalid identifiers, money, currency, and states difficult to represent.
- [ ] No speculative abstraction or later-release technology was added.

## Persistence and concurrency

- [ ] Critical invariants have appropriate PostgreSQL constraints as well as Java checks.
- [ ] Tenant-scoped uniqueness includes tenant ownership where required.
- [ ] Locking is narrow and justified; optimistic locking is the default.
- [ ] Race conditions are tested using real concurrent operations and final database assertions.
- [ ] Time comes from an injected `Clock` where behaviour depends on it.

## API, failure, and security

- [ ] Validation and RFC 7807 errors are stable, typed, and do not leak tenant data.
- [ ] Invalid transitions and conflicts leave persisted state unchanged.
- [ ] Ambiguous outcomes are not incorrectly converted to definitive failures.
- [ ] Logs are structured and exclude secrets or sensitive data.
- [ ] High-risk decisions have attributable actor and audit evidence when in scope.

## Tests and evidence

- [ ] Domain invariants and invalid transitions have focused unit tests.
- [ ] PostgreSQL-dependent behaviour uses Testcontainers, not H2 or database mocks.
- [ ] Failure, rollback, duplicate, concurrency, and recovery cases are covered where relevant.
- [ ] Spring Modulith/architecture tests cover new boundaries.
- [ ] Tests prove behaviour rather than mirror private implementation.
- [ ] Exact commands and results are recorded in the pull request.

## Documentation and scope

- [ ] Traceability and the active plan reflect the new evidence and remaining gaps.
- [ ] API, migration, operational, and known-limitation docs are updated when relevant.
- [ ] Any architectural deviation has an accepted ADR.
- [ ] The diff contains no unrelated refactoring, formatting, generated output, or secrets.
