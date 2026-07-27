# ADR-007: Use Keycloak for authentication

Status: Accepted - retrospective repository record  
Decision date: 2026-07-13  
Repository record date: 2026-07-27  
Decision owners: Product owner; Architecture owner  
Supersedes: None  
Superseded by: None

## Provenance

Technical Design and Architecture Specification v1.0 approved this decision on 13 July 2026. This file restores repository traceability for that accepted decision. It is not a recovered original and introduces no new architecture.

## Context

LedgerOps needs human and machine authentication without owning passwords, password recovery, multi-factor authentication, OAuth token issuance, or browser identity sessions. Implementing those capabilities inside the Core Platform would add security risk and distract from the financial and operational domain.

## Decision

Use Keycloak as the Identity Provider.

- Human Operations Web authentication uses OpenID Connect Authorization Code Flow with PKCE.
- Machine clients use OAuth 2.0 Client Credentials with a distinct client identity.
- LedgerOps stores no password or authentication factor.
- Keycloak owns authentication credentials, OIDC/OAuth sessions, client secrets, and token issuance.
- LedgerOps PostgreSQL owns application users, Tenant memberships, roles, permissions, Merchant scopes, local credential status, support sessions, authorization decisions, and audit evidence.
- Access tokens are short-lived, and the browser does not persist bearer tokens in localStorage.

Release 0.3 details are governed by ADR-022.

## Consequences

Positive:

- Authentication is delegated to a purpose-built identity system.
- LedgerOps can demonstrate OIDC/OAuth2 and service identities without storing passwords.
- Application authorization remains explicit and domain-owned.

Negative or costly:

- Local development and tests require Keycloak.
- Keycloak administration is an external consistency boundary and cannot share a PostgreSQL transaction.
- Provisioning, rotation, and revocation need durable recovery.

## Alternatives considered

### Build authentication in LedgerOps

Rejected because it increases security risk and adds no portfolio value compared with implementing correct application authorization.

### Store all roles and Tenant scope only in Keycloak

Rejected because memberships, Merchant scope, immediate suspension/revocation, support sessions, and audit are LedgerOps business data.

## Impact assessment

- Product requirements: IAM-01 through IAM-04; TEN-03 and TEN-04.
- Security: Keycloak authenticates; Core authorizes.
- Testing: real Keycloak integration, token validation, expiry, issuer/audience, and service-client tests.
- Operations: Keycloak health, configuration, backup, and provisioning recovery require runbooks.
- Documentation: Technical Specification, security model, API documentation, and ADR-022.

## Review conditions

Reconsider only through a superseding ADR if Keycloak cannot meet verified identity requirements or the deployed identity boundary changes materially.

## Approval

- Product owner: Approved in Technical Specification v1.0
- Architecture owner: Approved in Technical Specification v1.0
- Retrospective record introduces a new decision: No
