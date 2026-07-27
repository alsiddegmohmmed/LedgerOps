# Release 0.3 identity and request flow

```mermaid
sequenceDiagram
    participant Browser
    participant BFF as Operations Web BFF
    participant KC as Keycloak
    participant Core
    participant ID as identity::api
    participant Biz as Business module

    Browser->>BFF: Select /tenants/{tenantId}/...
    BFF->>KC: Authorization Code + PKCE
    KC-->>BFF: Tokens
    BFF-->>Browser: Opaque Secure HttpOnly session cookie
    Browser->>BFF: Tenant-scoped request + CSRF
    BFF->>Core: Access token + explicit tenant route
    Core->>Core: Validate issuer/audience/signature/time
    Core->>ID: Resolve user, membership, permissions, Merchant scope
    ID-->>Core: Immutable AuthorizedRequestContext
    Core->>Biz: Execute with context and resource selector
    Biz->>Biz: Verify ownership + business state
    Biz-->>Browser: Result / RFC 7807 problem
```

The selected Tenant is an untrusted selector. Current PostgreSQL authorization and resource ownership determine authority. Redis/BFF session state never grants business permission.
