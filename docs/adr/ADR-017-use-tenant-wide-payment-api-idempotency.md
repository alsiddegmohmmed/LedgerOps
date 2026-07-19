# ADR-017: Use tenant-wide Payment API idempotency

Status: Accepted

Date: 2026-07-19

Decision owners: Product owner; Architecture owner

Supersedes: Technical Specification v1.1 §5.7 Payment API idempotency boundary

Superseded by: None

## Context

The Product Definition v1.2 and Technical Specification v1.1 defined different Payment API idempotency boundaries:

- Product Definition PAY-02 and BR-04 use `tenantId + idempotencyKey`.
- Technical Specification §5.7 used `tenantId + merchantId + idempotencyKey`.

Both boundaries cannot govern the same API request. The difference changes whether two requests for different merchants under one tenant may reuse an idempotency key, so it cannot be resolved as an editorial variation. Payment implementation remains pending and no released Payment schema requires migration.

The roadmap also needed clearer release labels. Technical Specification §14.3 already scheduled Keycloak and authorization-related capabilities for Release 0.3, but repository summaries could be read as introducing security only in Release 1.0.

## Decision

The Payment API idempotency boundary is exactly:

```text
tenantId + idempotencyKey
```

The namespace is tenant-wide. Equivalent repeated requests under the same tenant and key return the original logical result.

Reusing the same tenant and key with materially different content, including a different merchant, returns an explicit idempotency conflict. `merchantId` is request content and is not part of the uniqueness boundary.

The Payment database schema must enforce a unique constraint on `(tenant_id, idempotency_key)`. Request comparison must include the merchant identity so that cross-merchant reuse under the same tenant and key is detected as conflicting content.

Release 0.3 introduces Keycloak, identity, tenant membership, permissions, merchant scope, authorization, and tenant-isolation enforcement. Release 1.0 completes security hardening and release evidence, including deployment controls, scanning, secrets, operational verification, and documentation.

## Consequences

Positive:

- One API rule now governs the Product Definition, Technical Specification, plan, traceability, and future database design.
- A tenant cannot create two logical Payments by reusing one key across merchants.
- Equivalent sequential and concurrent retries have one stable logical result.
- Release summaries distinguish identity and authorization delivery from final security hardening.

Negative or costly:

- Clients must coordinate idempotency keys across all merchants in a tenant.
- The Payment implementation must compare canonical request content before returning the original result.
- Tests must cover cross-merchant key reuse as a conflict, not as an independent request.

## Alternatives considered

### Use `tenantId + merchantId + idempotencyKey`

Rejected because it contradicts PAY-02 and BR-04 and permits the same tenant-wide key to identify more than one logical Payment.

### Accept any repeated request under the same tenant and key

Rejected because a materially different request must not receive an unrelated original result. Changed content must return an explicit conflict.

### Describe identity and authorization as Release 1.0 work

Rejected because the approved roadmap introduces Keycloak, identity, membership, permissions, merchant scope, authorization, and tenant-isolation enforcement in Release 0.3. Release 1.0 hardens and verifies that security baseline.

## Impact assessment

- Product requirements: PAY-02 and BR-04 retain their tenant-wide boundary; §6.2 now makes the tenant scope and cross-merchant conflict explicit.
- Data and migration: The future Payment schema requires unique `(tenant_id, idempotency_key)`. `merchant_id` must not appear in that uniqueness constraint. No released Payment migration changes.
- Security and tenancy: The boundary is tenant-scoped. Release 0.3 and Release 1.0 security responsibilities are stated explicitly without changing scope.
- Testing: Add equivalent sequential replay, materially different content, different-merchant conflict, coordinated concurrency, and final-database-state tests.
- Operations and reliability: Duplicate handling remains deterministic under concurrency and does not create duplicate financial effects.
- Cost and delivery: Documentation changes precede Payment implementation; no production code or later-release technology is introduced.
- Documentation: Product Definition v1.3, Technical Specification v1.2, the active release plan, traceability, README, and agent instructions are aligned.

## Review conditions

Reconsider this decision only through a Product Definition revision and a new ADR if the product adopts merchant-scoped idempotency or changes the meaning of equivalent request content.

## Approval

- Product owner: Approved
- Architecture owner: Approved
- Approved deviations recorded in authoritative documents: Product Definition v1.3 and Technical Specification v1.2
