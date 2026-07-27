# Release 0.3 Slice 1 - Identity, Audit, authenticated request context, and minimal BFF

Status: Pending  
Owner: One implementation owner  
Release: 0.3

## Outcome

Core validates Keycloak identities, maps them to application users, creates current request-scoped authorization context, and writes immutable Audit evidence. A minimal Operations Web BFF supports login/logout and explicit Tenant selection without browser-held bearer tokens.

## Authority

IAM-01 through IAM-03; AUD-01; BR-01, BR-07, BR-11, BR-16; ADR-007, ADR-008, ADR-022, ADR-025; Technical §§4, 6, 8, 13, Appendix E/F.

## Preconditions

- Run `./gradlew test` and `./gradlew check` before code changes.
- Inspect existing module declarations, API error mapping, correlation IDs, Testcontainers, Compose, and Flyway highest version.
- Pin Keycloak, Node.js LTS, Next.js, pnpm, and frontend package versions in repository files.

## Scope

Backend first:

- `identity` and `audit` modules/schemas;
- ApplicationUser and immutable `(issuer, subject)` link;
- Platform role and Tenant membership/role/scope foundations;
- explicit Permission catalogue and `scopeMode` (`TENANT_WIDE | MERCHANT_SET`);
- JWT issuer/audience/signature/time validation;
- human/service principal parsing;
- immutable request-scoped `AuthorizedRequestContext` published API;
- RFC 7807 401/403/404 distinctions;
- append-only audit records with database mutation/deletion protection;
- protect one existing read and one write path as vertical proof before broadening.

Frontend after backend passes:

- `applications/operations-web` foundation;
- BFF Authorization Code + PKCE;
- Redis-backed opaque server session and Secure HttpOnly cookie;
- CSRF protection;
- login/logout, Tenant selector shell, authenticated Core call;
- no bearer token in browser storage.

Excluded:

- invitations, credentials, support mode, full Tenant administration;
- business workflow UI beyond the auth shell;
- RLS.

## Smallest correct sequence

1. Domain/API contracts and permission/request-context tests.
2. Flyway/JPA adapters, JWT/Keycloak Testcontainers, Audit append-only persistence.
3. Protect representative Core APIs and run authorization negative matrix.
4. Add minimal BFF and Playwright login/Tenant-selection tests.

## Critical invariants

- token claims do not grant Tenant role authority;
- platform role is not a Tenant role assignment;
- Tenant selector is untrusted and revalidated every request;
- resource outside scope returns 404;
- Audit business record and sensitive change commit together;
- failed auth cannot reveal resource existence;
- Redis/session failure can only invalidate sessions.

## Verification

- valid/invalid issuer, audience, signature, expiry, not-before;
- unknown/deactivated user;
- active/suspended/revoked membership;
- Tenant/Merchant scope denial;
- multi-tab Tenant route isolation;
- audit update/delete rejection;
- Modulith/ArchUnit graph;
- BFF cookie flags, CSRF, no localStorage/sessionStorage token;
- full backend and frontend commands from master plan.

## Completion report

- Changed: Pending
- Verified: Pending
- Incomplete: Slice 1
- Deviations: None
