# Release 0.3 Slice 2 - Tenant/Merchant onboarding, membership, credentials, and support

Status: Completed
Owner: One implementation owner  
Release: 0.3

## Outcome

Platform Admin can create/activate/suspend/archive Tenants; Tenant Admin can manage Merchants, membership, configuration, and credentials; service access derives fixed Tenant/Merchant authority; support access is explicit, expiring, read-only, and audited.

## Authority

TEN-01 through TEN-04; IAM-04; PAY-01 authority change; AUD-01/02; BR-07/08/10/11/16; ADR-022, ADR-025.

## Scope

- one transaction creates pending Tenant, active initial Merchant, initial invitation/membership, audit/outbox;
- explicit Platform activation prerequisites;
- Merchant activate/suspend application workflows;
- Tenant versioned configuration and operational contacts;
- invitation expiry/revocation/one-time acceptance;
- membership transitions and last-active-Tenant-Admin invariant;
- role grant/scope non-escalation;
- deterministic Keycloak client provisioning operation;
- credential creation, one-time disclosure, rotation, local-first revocation, failure recovery;
- service principal mapping and current credential/Tenant/Merchant status check;
- replace Payment request authority with credential-derived Tenant/Merchant;
- explicit read-only support session with expiry and UI indicator;
- administration UI and immutable audit evidence for these actions. General audit search is owned by Slice 3 (`AUD-02`).

Excluded:

- Payment investigation views;
- manual Risk, Reversal, Reconciliation;
- general email delivery;
- mandatory RLS.

## Smallest correct sequence

1. Onboarding, membership, role/scope, Merchant lifecycle domain tests.
2. Identity/Tenancy/Merchant migrations and cross-module joined transaction tests.
3. Keycloak provisioning/recovery and protected service Payment contract.
4. Admin/credential/support UI and end-to-end tests.

## Critical invariants

- Tenant cannot activate without active initial Tenant Admin and Merchant;
- active Tenant never loses its last active Tenant Admin;
- Merchant cannot move Tenant;
- suspended Merchant/Tenant blocks new user-initiated activity but not committed recovery;
- grant cannot exceed actor authority/scope;
- secret is never stored in Core and never redisplayed after disclosure is consumed;
- deterministic operation cannot create duplicate Keycloak clients;
- local `REVOKED` denies immediately;
- support cannot mutate business state.

## Verification

- concurrent invitation acceptance/revocation/expiry boundary;
- reinvitation after revoked membership creates new history;
- grant escalation/self-escalation denial;
- last-admin race;
- Tenant activation/suspension/history access;
- Merchant suspension and credential denial;
- crash after Keycloak create, before local activation; retry convergence;
- lost secret response and required rotation;
- rotation activation/old local revocation atomicity;
- support expiry/read-only/visible audit;
- authenticated Payment request has no caller-authoritative Tenant/Merchant;
- full commands.

## Completion report

- Changed: Sequence item 2 implemented: forward Identity/Tenancy/Merchant migrations, persistence adapters, joined onboarding and lifecycle application services, transaction evidence, database constraints/locking, and Administration-owned onboarding/activation/suspension/archive HTTP boundaries. Sequence item 3 includes deterministic Keycloak credential provisioning/recovery, protected credential-derived Payment authority, credential create/rotate/revoke actions, safe metadata reads, and keyset-paginated credential collection. Sequence item 4 includes server-rendered Operations Web credential metadata, versioned Tenant configuration, operational-contact pages, scope-filtered Merchant and Membership read views, plus the protected invitation-revocation API, BFF action, and Operations Web action form; bearer tokens remain server-side and one-time secrets stay in client memory only. Local Operations Web development now has an additive Redis/Keycloak Compose runtime, a pinned Node version file, exact Core/BFF startup instructions, and handled deduplicated Redis connection errors while preserving the controlled authentication `503`.
- Verified: Focused Slice 2B HTTP/security/contract/module/logging tests passed; credential lifecycle, action, metadata, pagination, PostgreSQL migration/query, modularity, and OpenAPI tests passed; operational-contact, Merchant, Membership read, and invitation-revocation HTTP/filter/tenancy tests passed; Identity domain/persistence/acceptance and membership administration regression tests passed; the independent Provider persistence class passed in isolation; Operations Web `typecheck`, `lint`, 20 unit tests, and `build` passed on the pinned Node 24.18.0 runtime; invitation-revocation integration evidence covers atomic membership/invitation state, audit, outbox, Tenant-wide authorization, Merchant-scope hiding, and invalid confirmation; three authenticated Membership Playwright scenarios passed for safe reads, successful invitation revocation, and stale-tab `409` recovery; merged Release 0.2/0.3 Compose validation passed, and isolated Redis/Keycloak startup returned `PONG`, healthy service states, and imported-realm OIDC metadata before clean teardown; `git diff --check` passed. The full suite still reports the known order/resource-sensitive Provider persistence failures when run with the entire repository.
- Additional verified for the reconciled Slice 2 completion: invitation creation/reinvitation and role mutation UI, Merchant lifecycle administration and UI, explicit support-session expiry/read-only authorization/audit evidence, support-mode BFF/UI behavior, relevant Identity/Administration/Merchant backend tests, Operations Web typecheck, and 23 Operations Web unit tests. General audit search remains Slice 3 work under `AUD-02`.
- Boundary follow-up: final administration changes were reconciled with ADR-025 by removing Identity's direct Merchant/Tenancy implementation dependencies and Administration's direct Identity-internal dependencies. `ModularityTests` and `ArchitectureRulesTests` pass after this repair.
- Incomplete: None within the reconciled Slice 2 scope. General audit search remains explicitly deferred to Slice 3 (`AUD-02`).
- Deviations: None
