# Requirement traceability — Release 0.1

Authority: LedgerOps Product Definition v1.3 and LedgerOps Technical Design and Architecture Specification v1.2 only.

This matrix records derived implementation evidence. It does not modify either authoritative document.

This matrix tracks only requirements touched by Release 0.1. Product requirements not listed here remain part of the authoritative Product Definition and are not silently removed.

Status values: `Implemented`, `Partial`, `Planned`, `Deferred`, or `Blocked`.

| Requirement | Release 0.1 interpretation | Evidence | Status |
|---|---|---|---|
| TEN-01 | Release 0.1 implements tenant identity data and lifecycle mechanics only. The designated initial Merchant Admin, authenticated platform-administrator action, membership/role assignment, and authorised historical access require the Identity and authorization work scheduled by the Technical Specification for Release 0.3. | Current: domain lifecycle tests, Flyway schema, persistence adapter, transactional application use cases, typed failures, validated HTTP/OpenAPI lifecycle contract, correlated RFC 7807 failures, and PostgreSQL-backed HTTP/application tests. Release 0.1 remaining: cross-module suspended-write enforcement. Release 0.3 remaining: initial-admin designation and authorization evidence. | Partial |
| PAY-01 | create a stable tenant/merchant-owned payment with merchant reference, amount, currency, customer identifier, payment-method category, and idempotency key. Authorization is completed with the Release 0.3 identity boundary. | Current foundation: published merchant ownership reference and merchant-scoped customer reference. Remaining: payment domain/API contract and PostgreSQL integration tests covering every required request field and stable identifier/state. | Planned |
| PAY-02 | The Payment API idempotency namespace is tenant-wide. Equivalent requests under the same `tenantId + idempotencyKey` return the original logical result. Materially different content, including a different merchant, returns an explicit idempotency conflict. | unique `(tenant_id, idempotency_key)` database constraint; merchant-aware request fingerprint; sequential replay, conflicting-content, cross-merchant conflict, and coordinated concurrent Testcontainers tests that assert the final database state | Planned |
| PAY-03 | only the ADR-016 Payment transitions may occur; `REJECTED`, `FAILED`, and `REVERSED` are terminal, and provider, Reversal, and Reconciliation progress remain separate dimensions | Documentation reconciled in Product v1.3 §8.2, Technical v1.2 §5.3, and ADR-016. Payment aggregate and exhaustive domain transition tests are pending. | Planned |
| PAY-06 | one authorised full-payment Reversal workflow may be requested per `COMPLETED` Payment; failed retries remain in that workflow and add immutable provider attempts | Documentation reconciled in Product v1.3 §6.6/PAY-06, Technical v1.2 §5.4, and ADR-016. Reversal implementation remains scheduled for Release 0.3. | Deferred |
| PAY-08 | expose `REQUESTED`, `PROCESSING`, `FAILED`, and `COMPLETED` Reversal history; only completed reversal atomically compensates the ledger and changes Payment to `REVERSED` | Documentation reconciled in Product v1.3 PAY-08/§8.4, Technical v1.2 §5.4, and ADR-016. Reversal implementation remains scheduled for Release 0.3. | Deferred |
| RSK-01 | evaluate every eligible payment before provider processing and record every evaluated rule, triggered rule, score contribution, final score, and decision | deterministic evaluation and recorded-outcome tests | Planned |
| RSK-02 | return approve, manual review, or reject using tenant-configurable thresholds within platform-defined safe bounds | threshold-boundary, configuration-validation, and decision tests; the manual-review queue and human decision workflow are later-release work | Planned |
| LED-01 | balanced journal transaction with at least one debit and credit | domain/property tests plus PostgreSQL constraint/integration evidence | Planned |
| LED-02 | maintain tenant-scoped accounts with stable identifier, currency, status, and transaction history for the required demonstration categories | account domain/schema tests, tenant-isolation tests, and transaction-history integration tests | Planned |
| LED-03 | ledger posting links to the originating payment | bidirectional identifiers and integration test | Planned |
| LED-04 | posted financial history is immutable | update/delete prevention and compensating-reference tests | Planned |
| BR-01 | every business-owned record belongs to one tenant | Tenancy ownership exists; Merchant and Customer now have non-null `tenant_id`, scoped repositories, scoped uniqueness, immutable ownership assignment, and PostgreSQL cross-tenant isolation evidence. Payment, Risk, and Ledger ownership evidence remains. | Partial |
| BR-02 | money has explicit currency and decimal precision appropriate to that currency | Payment-module `Money` uses `BigDecimal`, explicit `Currency`, currency-defined precision, non-negative values, and same-currency arithmetic tests. Payment, ledger, and reversal adoption remains. | Partial |
| BR-03 | correct financial history using new records | immutable ledger design and compensation tests | Planned |
| BR-04 | one tenant and idempotency key identifies one logical payment; `merchantId` is not part of the uniqueness boundary, and reuse with a different merchant is conflicting content rather than a second payment | unique `(tenant_id, idempotency_key)` constraint, merchant-aware request fingerprint, cross-merchant conflict test, and concurrent final-state evidence | Planned |
| BR-10 | suspended tenants retain history but cannot create activity | `Tenant.canCreateOperationalActivity()` plus PostgreSQL-backed application and HTTP read evidence exist; enforcement remains for merchant, customer, and payment write use cases | Partial |
| BR-12 | time is consistent and displayed with locale/timezone context | UTC `Clock` bean exists; broader persistence/API evidence remains | Partial |
| BR-13 | Payment, Reversal, Reconciliation, and provider-attempt progress are separate dimensions | Authoritative documentation and ADR-016 are aligned; executable Payment evidence is planned and later-release Reversal/Reconciliation evidence is deferred. | Planned |
| BR-14 | only a completed full Reversal changes Payment from `COMPLETED` to `REVERSED`; partial and cumulative reversals are prohibited | Authoritative documentation and ADR-016 are aligned; atomic reversal and prohibition tests remain scheduled for Release 0.3. | Deferred |

## Technical decision evidence

| Technical section | Decision | Evidence/status |
|---|---|---|
| §4.1–4.4 | modular monolith, internal layering, owned schemas, no cross-table access | Tenancy and Merchant module declarations, published tenancy API, restricted Merchant dependency, separate schemas, and Modulith verification exist; expand with each module |
| §4.3 and §5.1 | Merchant owns profiles/state/configuration/limits, belongs to exactly one tenant, and does not live inside the Tenant aggregate | merchant identity/state foundation, mandatory tenant reference, owned schema, scoped repository, and isolation tests implemented; configuration and limits remain planned |
| §4.3, §5.1, and §8.6 | Customer owns simulated references, status, merchant relationship, risk attributes, and tokenized references while storing no real card credentials or unnecessary personal profiles | customer identity/reference/status foundation, mandatory tenant/merchant ownership, data-minimized owned schema, scoped repository, and isolation tests implemented; risk and tokenized attributes remain planned |
| §4.3 and §14.3 | Identity owns users, membership references, and role assignments; Release 0.3 introduces Keycloak, identity, tenant membership, permissions, merchant scope, authorization, and tenant-isolation enforcement | TEN-01 remains Partial in Release 0.1; Tenancy does not absorb identity-owned data |
| §5.2 | explicit value objects, `BigDecimal` Money with currency, same-currency arithmetic, non-negative values, and injected time | `TenantId`, `MerchantId`, `CustomerId`, customer reference, `Clock`, and Payment-module `Money` with precision/arithmetic invariant tests implemented |
| Product §8.2, BR-13; Technical §§5.3–5.4; ADR-016 | exact Payment and Reversal lifecycles with separate provider-attempt and Reconciliation dimensions | Documentation reconciled; Payment implementation and exhaustive lifecycle tests pending. Reversal and Reconciliation implementation remain later-release work. |
| Technical §§4.3, 5.1, 9.2–9.5; ADR-016 | Payment owns immutable Payment Attempt business records; Provider owns adapters, communication evidence, retries, ambiguity, and recovery orchestration | Documentation reconciled; implementation pending in the release sequence. |
| Product sensitive-action baseline; Technical §§8.3, 8.5, 13, 14.3, 17; ADR-016 | tenant-scoped capability, explicit confirmation, reason where applicable, immutable audit, and re-authentication/MFA where required; formal approval chains deferred | Documentation reconciled; security implementation remains scheduled by the release plan. |
| §5.5 | strict, single-currency double-entry ledger | Planned in ledger slice |
| §5.6 | atomic payment completion and ledger posting | Planned in atomic completion slice |
| Product PAY-02 and BR-04; Technical §5.7; ADR-017 | Payment API idempotency uses exactly `tenantId + idempotencyKey`; `merchantId` remains request content and is excluded from the uniqueness boundary | `Clock` exists; unique `(tenant_id, idempotency_key)` constraint, request comparison, replay, conflict, and concurrency evidence are planned |
| §14.3–14.4 | Release 0.3 introduces identity and authorization enforcement; Release 1.0 completes security hardening and release evidence | release descriptions aligned; implementation remains scheduled by the roadmap |
| §6.1–6.4 | one PostgreSQL database, module schemas, Flyway ownership | tenancy schema and Testcontainers migration test implemented |
| §13 | layered tests and critical verification | domain, application, HTTP contract, PostgreSQL, and Modulith foundations implemented; critical workflow tests planned |
| §14.1 | modular/PostgreSQL foundation; tenant/merchant/customer basics; payment, idempotency, state machine, synchronous risk; strict ledger and atomic completion; OpenAPI, structured logs, and tests | tracked completely in `docs/plans/release-0.1-transactional-core.md` |

## Updating this matrix

Mark a requirement `Implemented` only when its complete acceptance behaviour has executable evidence. A class or happy-path test alone is not enough. Every implementation pull request must update this matrix or explain why no mapped requirement changed.
