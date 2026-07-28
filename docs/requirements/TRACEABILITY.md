# Requirement traceability - Release 0.3

Authority: Product Definition v1.7, Technical Specification v1.7, ADR-022 through ADR-027.

| Requirement | Release 0.3 owner | Exit evidence | Status |
|---|---|---|---|
| TEN-01 | Slice 2 | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| TEN-02 | Slice 2 | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| TEN-03 | Slice 2 | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| TEN-04 | Slice 2 | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| IAM-01 | Slice 1 | ApplicationUser lifecycle, Keycloak JWT/OIDC validation, opaque Redis session, Secure HttpOnly cookie, CSRF, login/logout, and invalid-session tests in `src/test/java/com/ledgerops/identity` and `applications/operations-web/tests`. | Implemented for Slice 1; administration bootstrap remains outside this slice |
| IAM-02 | Slices 1-2 | Closed Tenant roles/permissions, PostgreSQL membership and Merchant scope resolution, and protected-path permission tests in `src/test/java/com/ledgerops/identity` and `src/test/java/com/ledgerops/protectedpaths`. | Implemented for Slice 1 foundation; administration coverage remains in Slice 2 |
| IAM-03 | Slice 1 | Explicit Tenant selection, PostgreSQL-owned `AuthorizedRequestContext`, stale/revoked/scope denial, and protected Tenant/Payment path tests. | Implemented |
| IAM-04 | Slices 2, 4, 5, 6, 8, 9, 10 | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| PAY-01 | Slice 2 | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| PAY-02 | Preserved R0.1 | Existing executable evidence remains required. | Implemented |
| PAY-03 | Preserved R0.1/R0.2 + Slices 4/6 | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| PAY-04 | Slices 3, 6, 8 | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| PAY-05 | Slice 3 | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| PAY-06 | Slice 6 | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| PAY-07 | Slice 3 | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| PAY-08 | Slice 6 | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| RSK-01 | Preserved R0.1 | Existing executable evidence remains required. | Implemented |
| RSK-02 | Preserved R0.1 + Slice 4 | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| RSK-03 | Slice 4 | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| RSK-04 | Slice 4 | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| RSK-05 | Slice 5 | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| PRV-01 | Slice 5/7 | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| PRV-02 | Slices 3/6 | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| PRV-03 | Slices 3/5/6 | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| PRV-04 | Slices 5/6 (ADR-026/ADR-023) | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| PRV-05 | Slice 5/10 | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| LED-01 | Preserved + Slices 6/8/9 | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| LED-02 | Preserved + ADR-023/024 clarification | Existing executable evidence remains required. | Implemented |
| LED-03 | Slices 3/6/8/9 | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| LED-04 | Slice 9 | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| LED-05 | Slice 3 | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| REC-01 | Slice 7 | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| REC-02 | Slice 8 | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| REC-03 | Slice 8 | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| REC-04 | Slice 8 | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| REC-05 | Slices 8/9 | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| CAS-01 | Slices 4/8 | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| CAS-02 | Slice 4 | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| CAS-03 | Slices 4/9 | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| CAS-04 | Slices 4/9 | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| CAS-05 | Slice 4 | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| OPS-01 | Slice 10 | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| OPS-02 | Slice 10 | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| OPS-03 | Slices 4/5/10 | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| OPS-04 | Slices 3/10 | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| NTF-01 | Slices 4/5/10 | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| NTF-02 | Deferred SHOULD | Product SHOULD; not release-blocking. | Deferred |
| NTF-03 | Deferred SHOULD | Product SHOULD; not release-blocking. | Deferred |
| AUD-01 | Slices 1-10 | Immutable Audit domain/persistence, append-only database protection, actor/principal/Tenant/correlation fields, and transactional Payment-create audit evidence in `src/test/java/com/ledgerops/audit` and `src/test/java/com/ledgerops/protectedpaths`. | Implemented for Slice 1 foundation; remaining sensitive actions are covered by later slices |
| AUD-02 | Slice 3 | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| AUD-03 | Slice 10 | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| AUD-04 | Slice 10 | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| DEV-01 | Slices 2/5/6/10 | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| DEV-02 | Slice 5 (ADR-026) | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| DEV-03 | Slices 5/10 | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| DEV-04 | Slice 10 | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| LOC-01 | Slice 10 | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| LOC-02 | Slice 10 | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |
| LOC-03 | Slices 3/4/6/8/9/10 | Domain, PostgreSQL/Testcontainers, authorization, failure/recovery, API/UI, and documentation evidence from the named slice. | Planned |

## Release-wide business rules

| Rule | Owner/evidence | Status |
|---|---|---|
| BR-01 | Tenant ownership and platform-wide exceptions across Slices 1-11. | Implemented for Slice 1 protected paths; regression required |
| BR-02 | Existing Money evidence plus Reversal/settlement/correction precision in Slices 6-9. | Planned regression |
| BR-03 | Existing immutability plus exact Reversal/correction compensation in Slices 6/9. | Planned regression |
| BR-04 | Existing tenant-wide Payment idempotency plus authenticated context in Slice 2. | Implemented; regression required |
| BR-05 | Existing Provider/result duplicate safety plus Reversal result tests in Slice 6. | Implemented; regression required |
| BR-06 | Existing retry safety plus manual Payment/Reversal controls in Slices 5/6. | Planned regression |
| BR-07 | Current actor and atomic audit across Slices 1-10. | Implemented for Slice 1 sensitive write; regression required |
| BR-08 | Exact role/permission/scope negative matrices across Slices 1-11. | Implemented for Slice 1 representative paths; regression required |
| BR-09 | Source-specific controlled Case resolution/closure in Slices 4/9. | Planned |
| BR-10 | Tenant/Merchant suspension versus committed recovery across Slices 2-11. | Planned |
| BR-11 | ACTIVE/DEACTIVATED application user and immutable authorship in Slices 1-3. | Implemented for Slice 1; regression required |
| BR-12 | Injected Clock, UTC persistence, locale/timezone display across Slices 1-11. | Implemented for Slice 1 time-dependent evidence; regression required |
| BR-13 | Separate Payment/Reversal/Reconciliation/Provider histories in Slices 3/6/8. | Planned |
| BR-14 | Full-only atomic Reversal completion in Slice 6. | Planned |
| BR-15 | Immutable reruns and one locked current pointer in Slice 8. | Planned |
| BR-16 | Platform/support authority boundary in Slices 1/2. | Implemented for Slice 1 authorization boundary; administration/support coverage remains in Slice 2 |
| BR-17 | Stable settlement instruction/application and exact templates in Slice 8. | Planned |
| BR-18 | Narrow settlement-adjustment correction in Slice 9. | Planned |

## Cross-cutting closure evidence

### Slice 1 closure

Slice 1 is complete. Executable evidence covers ApplicationUser identity and lifecycle, immutable Keycloak issuer/subject links, JWT signature/issuer/audience/time/nonce/principal validation, PostgreSQL-owned Tenant membership/role/permission/Merchant scope resolution, request context construction, append-only Audit persistence, and the protected representative paths `GET /api/v1/tenants/{tenantId}` and `POST /api/v1/payments`. The Payment write and its Audit record commit atomically; idempotent replay creates no duplicate Payment or Audit record.

Backend verification: `./gradlew test --console=plain` passed with 419 tests; `./gradlew check --console=plain` passed; `git diff --check` passed. Frontend verification: `pnpm install --frozen-lockfile`, `pnpm lint`, `pnpm typecheck`, `pnpm test` (4 tests), `pnpm exec playwright test` (1 test), and `pnpm build` passed.

The Operations Web implementation is intentionally only an authentication shell: Authorization Code + PKCE, server-side Redis session, Secure HttpOnly cookie, CSRF/same-origin protection, explicit untrusted Tenant selection, and server-side Core Tenant lookup. Dashboard, administration, audit search, and business workflow pages remain later-slice work.

- Module dependencies, messaging, scenario/health, and projection identities: ADR-025 and ADR-027, all slices, final Modulith/contract gate.
- Manual Payment retry and merchant webhook security: ADR-026, Slice 5.
- Stable SettlementPostingInstruction and BatchFamilyControl: ADR-024, Slices 8-9.
- Complete decision mapping: `release-0.3-decision-matrix.md`.
- Closure audit: `../reviews/release-0.3-closure-review.md`.
