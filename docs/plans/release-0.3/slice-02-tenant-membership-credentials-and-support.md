# Release 0.3 Slice 2 - Tenant/Merchant onboarding, membership, credentials, and support

Status: Active
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
- administration UI and audit search for these actions.

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

- Changed: Sequence item 2 implemented: forward Identity/Tenancy/Merchant migrations, persistence adapters, joined onboarding and lifecycle application services, transaction evidence, database constraints/locking, and Administration-owned onboarding/activation/suspension/archive HTTP boundaries. Sequence item 3 now includes deterministic Keycloak credential provisioning/recovery, protected credential-derived Payment authority, credential create/rotate/revoke actions, safe metadata reads, and keyset-paginated credential collection. Sequence item 4 has started with a server-rendered Operations Web credential metadata list that keeps bearer tokens server-side and never displays secrets.
- Verified: Focused Slice 2B HTTP/security/contract/module/logging tests passed; credential lifecycle, action, metadata, pagination, PostgreSQL migration/query, modularity, and OpenAPI tests passed; `GRADLE_USER_HOME=/Users/Siddegx/.ledgerops-slice-2b-gradle-home ./gradlew check --console=plain` passed; Operations Web `typecheck`, `lint`, 9 unit tests, and `build` passed; `git diff --check` passed. The current frontend verification environment reports Node 22.22.3, while the project pins Node 24.18.0 or newer within 24.x.
- Incomplete: Credential create/rotate/revoke UI, membership/Merchant/configuration UI, explicit support session and UI indicator, audit search, and the corresponding end-to-end tests remain. Slice 2 is not complete.
- Deviations: None
