# ADR-008: Use shared-database multi-tenancy with optional PostgreSQL RLS

Status: Accepted - retrospective repository record  
Decision date: 2026-07-13  
Repository record date: 2026-07-27  
Decision owners: Product owner; Architecture owner  
Supersedes: None  
Superseded by: None

## Provenance

Technical Design and Architecture Specification v1.0 approved this decision on 13 July 2026. This file restores repository traceability. It is not a recovered original and introduces no new architecture.

## Context

LedgerOps must prove absolute Tenant isolation while remaining achievable for one engineer. Database-per-Tenant and schema-per-Tenant designs would multiply migrations, connection management, testing, and operations without solving a demonstrated scale or regulatory requirement.

## Decision

Use one Core PostgreSQL database with module-owned schemas and mandatory row-level Tenant ownership.

- Every Tenant-owned table contains non-null `tenant_id`.
- Application repositories expose Tenant-scoped query and mutation methods.
- Application authorization validates Tenant, Merchant scope, resource ownership, permission, and business state.
- Tenant-scoped uniqueness includes `tenant_id` where the business identity is Tenant-local.
- Cross-module table access remains prohibited even though modules share a database.
- PostgreSQL Row-Level Security may be added as defence in depth after the application model is stable.
- When RLS is enabled, the application uses transaction-local Tenant context and verifies connection-pool context cannot leak.

Release 0.3 authorization details are governed by ADR-022.

## Consequences

Positive:

- Short cross-module transactions can preserve financial invariants.
- One migration topology remains manageable.
- Tenant isolation can be tested at application, repository, constraint, and optional RLS layers.

Negative or costly:

- Every repository and query must preserve Tenant scope correctly.
- A missing Tenant predicate is a critical defect.
- Optional RLS requires careful transaction-local context and pool-reuse tests.

## Alternatives considered

### Database per Tenant

Rejected because it adds disproportionate operational and migration complexity for the portfolio scale.

### Schema per Tenant

Rejected because module ownership already uses schemas and per-Tenant schema proliferation would complicate migrations and queries.

### Rely only on frontend visibility

Rejected because frontend restrictions are not security controls.

## Impact assessment

- Product requirements: BR-01, IAM-03, tenant-isolation quality criteria.
- Data: non-null Tenant ownership and Tenant-scoped constraints.
- Security: application checks are mandatory; RLS is optional defence in depth.
- Testing: cross-Tenant negative matrix, repository architecture checks, concurrency, and connection-pool leakage tests if RLS is enabled.
- Operations: shared database backup/restore and monitoring remain straightforward.

## Review conditions

Reconsider if regulatory, isolation, or measured scale evidence requires stronger physical separation.

## Approval

- Product owner: Approved in Technical Specification v1.0
- Architecture owner: Approved in Technical Specification v1.0
- Retrospective record introduces a new decision: No
