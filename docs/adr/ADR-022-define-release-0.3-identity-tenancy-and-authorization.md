# ADR-022: Define Release 0.3 identity, tenancy, authorization, and sandbox credential semantics

Status: Accepted  
Date: 2026-07-27  
Decision owners: Product owner; Architecture owner  
Supersedes: Conflicting Tenant/Merchant and role wording in Product Definition v1.6 and Technical Specification v1.6  
Superseded by: None

## Summary

Release 0.3 introduces Keycloak authentication, application-owned authorization, multi-Merchant Tenant administration, sandbox credentials, audited support access, and the Operations Web browser-session boundary.

This ADR resolves the Product v1.6 statement that one Tenant represents one merchant organisation, the Technical v1.6 Merchant-scope model, the implemented one-to-many Merchant persistence, and the mismatched Product/Technical role catalogues.

## Context

### Authority

- Product Definition v1.6: TEN-01 through TEN-04; IAM-01 through IAM-04; PAY-01; §9; BR-01, BR-07, BR-08, BR-10, BR-11, and BR-16.
- Technical Specification v1.6: §§3.1-3.3, 4.1-4.4, 6.1-6.3, 8.1-8.6, 13, 14.3, 16, and 17.
- ADR-007: Keycloak for authentication.
- ADR-008: shared-database tenancy with optional PostgreSQL RLS.
- ADR-017: tenant-wide Payment API idempotency.

### Conflicts

Product v1.6 says a Tenant represents one merchant organisation. Technical v1.6 defines Tenant membership with optional Merchant scope, a Merchant Admin restricted to assigned Merchants, and service identities. ADR-017 defines cross-Merchant idempotency conflict inside one Tenant. The repository already permits more than one Merchant for one Tenant.

Product and Technical role catalogues also disagree about Tenant Admin, Reconciliation Analyst, Integration Developer, and Service Account.

### External consistency boundary

Keycloak and Core PostgreSQL cannot share one atomic transaction. Client creation, secret generation, disablement, and user authentication therefore require durable, idempotent recovery without holding a database transaction open during network calls.

## Decision

### Tenant and Merchant model

A Tenant is one isolated LedgerOps customer organisation and the primary security boundary.

A Tenant owns one or more Merchant profiles. A Merchant represents a business identity, brand, operating unit, or integration profile. A Merchant belongs permanently to exactly one Tenant and cannot be transferred.

Every Payment belongs to exactly one Tenant and one Merchant. Tenant-wide Payment idempotency remains `tenantId + idempotencyKey`; Merchant identity remains canonical request content.

Merchant status remains exactly `ACTIVE | SUSPENDED`.

- `ACTIVE -> SUSPENDED`
- `SUSPENDED -> ACTIVE`

A suspended Merchant cannot create new Payments, credentials, or Merchant configuration changes. Previously committed Provider recovery, Payment completion, Reversal processing, reconciliation, and correction recovery continue because stopping them could leave financial state unresolved.

A `PENDING_ACTIVATION` Tenant permits only onboarding/configuration actions by its active Tenant Admin and Platform lifecycle operations; it permits no Payment, Provider, Reversal, settlement, Reconciliation, correction, or report-export action. A suspended or archived Tenant blocks user-initiated Tenant business writes. Previously committed idempotent recovery work continues. Historical reads remain available only to currently authorised users or an explicit Platform support session.

### Tenant onboarding and lifecycle prerequisites

The first Platform Admin is provisioned only through a deterministic local/test realm import plus an idempotent Core bootstrap mapping `(issuer, subject) -> PLATFORM_ADMIN`. No public or Tenant API can create a Platform Admin. The bootstrap is profile/configuration controlled, creates no password, and is disabled after the mapping exists.

Tenant creation is a Platform Admin operation. One Core PostgreSQL transaction creates:

- the `PENDING_ACTIVATION` Tenant;
- its initial `ACTIVE` Merchant;
- the initial Tenant Admin invitation and `INVITED` membership; and
- immutable audit evidence.

The `administration` module owns this cross-module onboarding orchestration. It calls published Tenancy, Merchant, Identity, and Audit APIs in one Core transaction and performs no external Keycloak call. Tenancy, Merchant, and Identity continue to own their aggregates and persistence. Identity itself has no business-module dependency, so protected business modules may depend on `identity::api` without a cycle.

Administration also owns the Platform-facing activation orchestration: it validates current Platform authority and onboarding prerequisites, then calls the published Tenancy lifecycle API. Tenant activation remains an explicit Platform Admin action. Activation is denied unless:

- the initial Tenant Admin membership is `ACTIVE`;
- at least one Merchant is `ACTIVE`; and
- the Tenant has no unresolved onboarding consistency error.

Reactivation from `SUSPENDED` applies the same prerequisites.

### Role model

Platform and Tenant roles are separate scopes.

Platform role catalogue:

- `PLATFORM_ADMIN`

Tenant role catalogue:

- `TENANT_ADMIN`
- `MERCHANT_ADMIN`
- `OPERATIONS_AGENT`
- `RISK_ANALYST`
- `RECONCILIATION_ANALYST`
- `AUDITOR`
- `VIEWER`
- `INTEGRATION_DEVELOPER`

`SERVICE_ACCOUNT` is a machine identity category, not a human role.

`PLATFORM_ADMIN` is not assignable through a Tenant membership. It has Tenant lifecycle, Provider-scenario, platform-health, and explicit read-only support capabilities. It has no implicit Tenant business authority.

Application services check explicit permissions, not role names. Release 0.3 uses this closed permission catalogue:

```text
tenant:read
tenant:configure
tenant:membership-manage
tenant:role-manage
merchant:create
merchant:read
merchant:configure
merchant:suspend
credential:manage
payment:read
payment:note-add
payment:retry
reversal:request
reversal:retry
risk:read
risk:review-assign
risk:review-decide
risk:configuration-manage
provider:read
provider:health-read
settlement:upload
reconciliation:read
reconciliation:run
reconciliation:promote
case:read
case:assign
case:update
case:resolve
case:close
correction:request
ledger:read
audit:read
report:read
report:export
notification:read
webhook:endpoint-manage
webhook:test-trigger
support:tenant-read
```

Platform-only capabilities for Tenant lifecycle, Provider-scenario administration, platform health, and Platform-only audit evidence (`platform:audit-read`) are stored as Platform permissions and cannot be granted through Tenant roles. The exact Platform permissions, Tenant-role permission mappings, scope constraints, and role-assignment authority are normative in `docs/security/release-0.3-role-permission-matrix.md`.

Release 0.3 has no direct per-user permission grants, deny rules, user-defined roles, or generic policy language. Human permissions come only from the closed role mapping. A sandbox service credential receives exactly the machine permission `payment:create` for its fixed Tenant and Merchant.

A Tenant role assignment has an explicit scope mode:

- `TENANT_WIDE`; or
- `MERCHANT_SET` with at least one Merchant belonging to the membership Tenant.

An empty Merchant set never means Tenant-wide authority. `TENANT_ADMIN` and `RECONCILIATION_ANALYST` assignments must be `TENANT_WIDE`. `MERCHANT_ADMIN` and `INTEGRATION_DEVELOPER` assignments must use `MERCHANT_SET`. `OPERATIONS_AGENT`, `RISK_ANALYST`, `AUDITOR`, and `VIEWER` may use either scope when their permissions support it.

A grant cannot exceed the inviter/administrator's effective permissions and scope. A Tenant Admin cannot create a Platform Admin. A Merchant Admin cannot grant Tenant-wide authority or a role outside the Merchant Admin's delegated permissions.

An active Tenant must retain at least one active Tenant Admin. Suspending or revoking the last active Tenant Admin is denied unless a Platform Admin is simultaneously suspending or archiving the Tenant through the authorised lifecycle workflow.

### Role capability boundaries

- `TENANT_ADMIN`: Tenant-wide memberships, role assignments, Tenant configuration, Merchant administration, sandbox credentials, and Tenant reports.
- `MERCHANT_ADMIN`: assigned-Merchant configuration, activity, credentials where delegated, reporting, and explicitly permitted Reversal requests. It cannot change Tenant-wide Risk configuration.
- `OPERATIONS_AGENT`: Payment investigation, notes, cases, and safe operational controls.
- `RISK_ANALYST`: manual review. Tenant-wide Risk configuration requires a Tenant-wide assignment plus `risk:configuration-manage`.
- `RECONCILIATION_ANALYST`: Tenant-wide settlement, reconciliation, discrepancies, cases, and permitted corrections.
- `AUDITOR`: scoped read-only evidence, Ledger, audit, reconciliation, and exports.
- `VIEWER`: scoped read-only dashboards and records.
- `INTEGRATION_DEVELOPER`: assigned-Merchant API activity, credentials where delegated, and sandbox merchant-webhook testing.

### Authentication ownership

Keycloak owns:

- passwords and authentication factors;
- OIDC sessions and token issuance;
- OAuth clients and client secrets;
- password recovery; and
- the external identity-provider lifecycle.

LedgerOps PostgreSQL owns:

- application users;
- immutable `(issuer, subject)` human identity links;
- Tenant memberships and invitations;
- role assignments, permissions, and Merchant scopes;
- sandbox credential metadata and local status;
- support sessions;
- authorization decisions; and
- immutable audit evidence.

LedgerOps stores no password. Email is invitation/contact evidence; after linkage, `(issuer, subject)` is the authenticated human identity. Application user status is exactly `ACTIVE | DEACTIVATED`. `ACTIVE -> DEACTIVATED` is terminal in Release 0.3. Users are never deleted; deactivation blocks access while immutable authorship and audit references remain readable.

### Membership and invitation model

Membership status is exactly:

- `INVITED`
- `ACTIVE`
- `SUSPENDED`
- `REVOKED`

Allowed transitions:

```text
INVITED   -> ACTIVE | REVOKED
ACTIVE    -> SUSPENDED | REVOKED
SUSPENDED -> ACTIVE | REVOKED
```

`REVOKED` is terminal. Reinvitation creates new membership and invitation records.

Invitation and membership are separate. An invitation:

- belongs to one Tenant and intended email;
- preserves proposed roles and scopes;
- expires after seven days;
- can be revoked;
- stores only a cryptographic token hash;
- is one-time use; and
- requires an authenticated Keycloak identity with a verified matching email.

Acceptance atomically links the Keycloak identity, activates the membership, creates role assignments/scopes, consumes the invitation, and writes audit evidence in Core PostgreSQL.

Real email delivery is not required in Release 0.3. The controlled demonstration UI may expose an invitation link without logging or exporting its token.

### Human Operations Web session

Operations Web uses OIDC Authorization Code Flow with PKCE through a Next.js backend-for-frontend.

The browser receives only an opaque `Secure`, `HttpOnly`, appropriately `SameSite` session cookie. Access and refresh tokens remain in a server-side BFF session store. Release 0.3 may use Redis for this ephemeral session state; Redis is not authorization truth. Redis loss invalidates sessions and requires reauthentication but cannot grant authority or change business state.

Browser JavaScript never stores bearer tokens in localStorage or sessionStorage.

Mutation requests use CSRF protection in addition to SameSite cookies. Core validates issuer, audience, signature, and time claims.

### Tenant context

Human Tenant context is explicit in the route or request, for example:

```text
/operations/tenants/{tenantId}/...
/api/v1/tenants/{tenantId}/...
```

The BFF may remember the last Tenant only as a user-interface preference. Core treats the supplied Tenant ID as an untrusted selector and validates, on every protected request:

1. token validity;
2. application user mapping;
3. active membership in the selected Tenant;
4. Tenant status;
5. required permission;
6. Merchant scope; and
7. actual resource Tenant/Merchant ownership.

Core creates an immutable request-scoped authorization context. It is not global, shared between threads, or cached across requests in Release 0.3.

For service accounts, Tenant and Merchant are derived from the active sandbox credential. Client-supplied Tenant or Merchant values cannot override them.

### Sandbox credential model

Each sandbox credential belongs to exactly one Tenant, one Merchant, and one deterministic Keycloak confidential client identity.

Credential status is exactly:

- `PROVISIONING`
- `ACTIVE`
- `FAILED`
- `REVOKED`

Allowed transitions:

```text
PROVISIONING -> ACTIVE | FAILED | REVOKED
FAILED       -> PROVISIONING | REVOKED
ACTIVE       -> REVOKED
REVOKED      -> terminal
```

Only `ACTIVE` credentials authorize requests. Core checks current local status and current Tenant/Merchant status for every service request, so local revocation is immediate even when a token remains cryptographically valid.

LedgerOps stores credential ID, Tenant, Merchant, label, deterministic Keycloak client ID, status, creator, timestamps, replacement relationship, provisioning operation identity, and audit evidence. It does not store the Keycloak client secret.

Credential creation uses one durable operation identity:

1. commit `PROVISIONING` and the operation record;
2. create or reconcile the deterministic Keycloak client outside a database transaction;
3. obtain the client secret only while the disclosure state is `PENDING`;
4. commit `ACTIVE` and mark disclosure consumed before attempting the HTTP response; and
5. return the secret once.

A lost response does not permit redisplay; the user rotates the credential. A crash before activation is recovered through the same operation and deterministic Keycloak client identity. Keycloak administration failure changes the local operation to recoverable `FAILED`; retry reuses the same operation instead of creating another client.

Rotation creates a replacement credential. The replacement is provisioned first. Replacement activation and local revocation of the old credential commit together. Disabling the old Keycloak client occurs outside the transaction and is retryable.

Revocation commits `REVOKED` locally before Keycloak cleanup.

### Payment API authority

The protected `POST /api/v1/payments` contract derives Tenant and Merchant from the active credential. `tenantId` and `merchantId` are removed from the client request body in the Release 0.3 contract.

The accepted Payment idempotency boundary remains `tenantId + idempotencyKey`. Merchant is canonical request content; cross-Merchant reuse under one Tenant key is an explicit conflict.

### Support mode

Platform Admin has no implicit Tenant business access.

A support session requires Tenant, reason, start/expiry, visible UI indicator, actor, Keycloak `auth_time` no older than five minutes, and immutable audit evidence. Maximum duration is 30 minutes, it cannot be extended, and a later session requires a new reason. It grants only `support:tenant-read` and cannot submit Payments, decide Risk, request/retry Reversals, resolve discrepancies, manage memberships/credentials, or create financial effects.

### Enforcement layers

- API: authentication and coarse endpoint permission.
- Application: definitive permission, Tenant, Merchant, resource ownership, and business-state checks.
- Repository: mandatory Tenant-scoped methods.
- Database: non-null Tenant ownership and Tenant-scoped uniqueness.
- Frontend: usability only.
- PostgreSQL RLS: optional defence in depth after the application model stabilises.

Redis, Kafka, browser state, and token role claims are never authorization truth.

### Failure semantics

- missing, invalid, or expired authentication: `401`;
- known identity without permission or with suspended/revoked membership: typed `403`;
- suspended Tenant user-initiated write: typed `403`;
- resource outside effective Tenant/Merchant scope: `404`;
- invitation, lifecycle, or provisioning conflict: `409`;
- recoverable Keycloak administration outage: typed `503` with durable operation state.

All external errors use RFC 7807-style problems and correlation IDs.

## Consequences

Positive:

- Tenant/Merchant cardinality matches the repository and Merchant-scoped authorization.
- Platform and Tenant authority cannot be confused.
- Immediate credential and membership revocation does not depend on token expiry.
- Multi-tab Tenant usage cannot silently switch authority.
- Keycloak failure cannot hold a PostgreSQL transaction open.

Negative or costly:

- Existing unauthenticated Release 0.1/0.2 API examples are superseded by authenticated Release 0.3 contracts.
- Every protected request performs current application authorization checks.
- Keycloak provisioning and BFF sessions add operational dependencies.
- Multi-Merchant onboarding and role/scoping tests are extensive.

## Alternatives considered

### One Merchant per Tenant

Rejected because it contradicts the implemented model, Merchant scopes, and ADR-017 cross-Merchant behavior.

### Store Tenant roles only in Keycloak

Rejected because membership, Merchant scope, immediate revocation, support mode, and application audit are LedgerOps business data.

### Trust a Tenant header or request body

Rejected because a client-supplied selector is not proof of authority.

### Browser-held bearer tokens

Rejected because the approved BFF boundary keeps bearer tokens inaccessible to browser JavaScript.

### Distributed transaction with Keycloak

Rejected because Keycloak cannot join the Core transaction.

## Impact assessment

- Product: Tenant/Merchant definitions, roles, TEN-01 through TEN-04, IAM requirements, PAY-01, RSK-05, and suspension behavior.
- Data: forward migrations for `identity.*`, `audit.*`, Merchant lifecycle application services, and credential operation records.
- API: authenticated human/service contracts and derived service context.
- Testing: Keycloak integration, exact role/permission/scope matrices, last-admin invariant, multi-tab Tenant context, credential failure/recovery, immediate revocation, support-mode recent-auth/expiry, audit atomicity, and module boundaries.
- Documentation: Product v1.7, Technical v1.7, security model, OpenAPI, diagrams, traceability, demo, and runbooks.

## Review conditions

Reconsider through a new ADR if a credential must span Merchants, user-defined roles are required, the BFF is removed, mandatory RLS is justified, or measured authorization performance requires a cache design.

## Approval

- Product owner: Approved by delegated instruction to finalise Release 0.3 documentation and commit it to the repository on 2026-07-27
- Architecture owner: Approved
- Approved deviations recorded in authoritative documents: Product Definition v1.7 and Technical Specification v1.7
