# Release 0.3 security and authorization model

Authority: Product Definition v1.7, Technical Specification v1.7, ADR-007, ADR-008, ADR-022, ADR-026.

## Sources of truth

| Concern | Authority |
|---|---|
| Passwords, MFA, OIDC sessions, OAuth clients/secrets, token issuance | Keycloak |
| Users, identity links, memberships, platform/Tenant roles, permissions, Merchant scopes | Core PostgreSQL `identity` |
| Sandbox credential current status | Core PostgreSQL |
| Tenant/Merchant/resource ownership | Owning Core module |
| Sensitive action evidence | Core PostgreSQL `audit` |
| Browser session state | BFF server-side Redis session |
| Financial/business state | Owning PostgreSQL module |

Redis, Kafka, frontend state, and token role claims are never authorization or financial truth.

## Role scopes

- Platform role: `PLATFORM_ADMIN`; never a Tenant membership role.
- Tenant roles: `TENANT_ADMIN`, `MERCHANT_ADMIN`, `OPERATIONS_AGENT`, `RISK_ANALYST`, `RECONCILIATION_ANALYST`, `AUDITOR`, `VIEWER`, `INTEGRATION_DEVELOPER`.
- Machine identity: one sandbox credential bound to one Tenant and one Merchant.

A Tenant role assignment has `TENANT_WIDE` or a non-empty `MERCHANT_SET`. No null/empty scope implies Tenant authority.

## Human request

```text
Browser -> BFF -> Keycloak Authorization Code + PKCE
Browser <- opaque Secure HttpOnly session cookie
BFF -> Core with access token + explicit Tenant route
Core -> validate JWT -> map user -> current membership/permission/scope -> resource owner -> business rules
```

The BFF uses CSRF protection. Browser JavaScript never receives or persists bearer tokens.

## Service request

```text
Merchant system -> Keycloak Client Credentials
Merchant system -> Core with access token
Core -> map authenticated client to ACTIVE local credential
Core -> derive fixed Tenant + Merchant
Core -> verify Tenant/Merchant status and operation permission
```

Request bodies/headers cannot override credential authority.

## Authorization checks

Every protected use case checks:

1. authenticated subject/client;
2. active local user/credential;
3. active membership for human requests;
4. Tenant status and Merchant status;
5. explicit permission;
6. scope mode and Merchant membership;
7. actual resource ownership;
8. business-state eligibility;
9. confirmation/reason for sensitive actions.

Release 0.3 performs current PostgreSQL authorization checks per request; there is no cross-request permission cache.

## Suspension/recovery

- Tenant or Merchant suspension blocks new user-initiated activity.
- Previously committed idempotent Provider, Payment, Reversal, Reconciliation, correction, outbox, and inbox recovery continues.
- Historical reads remain permission-scoped.
- Local credential/membership revocation takes effect immediately, regardless of token expiry.

## Failure policy

- `401`: missing/invalid/expired authentication.
- `403`: known identity lacks permission, membership is suspended/revoked, or a write is blocked by Tenant/Merchant status.
- `404`: resource is outside effective Tenant/Merchant scope.
- `409`: lifecycle/idempotency/provisioning conflict.
- `503`: durable external identity provisioning temporarily unavailable.

Problems include correlation ID, effect, retryability, and next action without exposing secrets or resource existence.

## Sensitive actions

Tenant lifecycle, membership/role changes, credentials, Risk decisions/configuration, Provider scenarios, manual Payment retry, Reversal request/retry, reconciliation rerun/promotion, correction, Case resolution/closure, merchant webhook endpoint/secret, reports/exports, and support sessions require appropriate capability and audit. Confirmation/reason applies where Product requires.

Formal maker-checker approval remains beyond baseline.

## Support mode

Platform Admin support is explicit, expiring, read-only, visibly indicated, and audited. It grants only `support:tenant-read` and cannot perform business mutations.

## Merchant webhook security

- signing secret encrypted with authenticated encryption and external master key;
- plaintext shown once and never logged/exported;
- HTTPS production-like endpoints only;
- block loopback/private/link-local/metadata/multicast networks, credentials in URL, redirects, and DNS rebinding;
- local-only profile may explicitly allow localhost;
- stable event ID/HMAC/timestamp; at-least-once delivery; bounded retries.

## Verification matrix

At minimum test:

- token issuer/audience/signature/time failures;
- user/credential/membership/Tenant/Merchant status changes between requests;
- last active Tenant Admin;
- grant/scope escalation;
- cross-Tenant and cross-Merchant resources for every protected module;
- multi-tab Tenant selector;
- support read-only and expiry;
- CSRF/cookie/token storage;
- provisioning crash/retry/secret disclosure/rotation/revocation;
- webhook encryption/SSRF/HMAC/retry;
- Audit append-only behavior.


## Support session policy

Platform support requires `auth_time` within five minutes, an explicit Tenant and reason, and a maximum 30-minute non-extendable session. It grants only read-only `support:tenant-read`; every read is visibly indicated and audited.


## Tenant status authorization matrix

- `PENDING_ACTIVATION`: Platform lifecycle plus active Tenant Admin onboarding/configuration only; no Payment, Provider, Reversal, settlement, Reconciliation, correction, or export.
- `ACTIVE`: permitted actions according to current role/scope/business state.
- `SUSPENDED`: authorised historical read only; no new user action; already committed idempotent recovery continues.
- `ARCHIVED`: authorised historical read only; terminal lifecycle; no reactivation or new activity.

The first Platform Admin is a deterministic environment/realm bootstrap mapping; no public endpoint creates Platform authority.
