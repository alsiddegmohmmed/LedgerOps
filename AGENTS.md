# LedgerOps agent instructions

## Mission and authority

LedgerOps is a production-style portfolio project for learning and demonstrating Java, Spring Boot, PostgreSQL, domain-driven design, modular monoliths, financial correctness, distributed processing, security, reconciliation, and operational product engineering.

Read before implementation:

1. `docs/product/LedgerOps_Product_Definition_Official_v1.7.docx` - what the product must do.
2. `docs/architecture/LedgerOps_Technical_Design_and_Architecture_Specification_v1.7.docx` - approved design.
3. Accepted ADR-022 through ADR-027 - Release 0.3 material decisions.
4. Retrospective ADR-007, ADR-008, ADR-010, and ADR-014 - restored accepted provenance.
5. `docs/plans/release-0.3-financial-operations.md`, `docs/plans/release-0.3/ORCHESTRATION.md`, and the active slice file.
6. `docs/requirements/release-0.3-decision-matrix.md` and `docs/requirements/TRACEABILITY.md`.
7. `docs/reviews/release-0.3-closure-review.md` - resolved conflicts and residual implementation risks.

Precedence: Product Definition, Technical Specification, accepted ADRs, active plan, code.

Stop only for a real conflict/change to an approved Product requirement, module/data ownership, financial identity/template, consistency boundary, security boundary, technology baseline, or release sequence. Ordinary implementation details consistent with authority are decided by the implementation owner without repeatedly asking the Product owner.

## Current scope

**Active milestone: Release 0.3 - Identity and Financial Operations.**

Slices 0 through 7 are complete for their approved scope. Slice 6's asynchronous Provider completion/retry browser coverage remains outstanding, and Slice 7's live MinIO/browser walkthrough remains an environment-level release check. **Slice 8 is next.** Do not implement later-slice behavior early.

Release 0.3 includes Keycloak/Core authorization, Tenant/Merchant administration, credentials, audited support, Operations Web, Payment/Ledger operations, manual Risk/Provider controls, full Reversal, merchant webhook testing, settlement ingestion, Reconciliation, Cases, narrow corrections, dashboard/live activity/notifications/reports, developer workflows, and Arabic/English parity.

Release 0.3 excludes public cloud/Kubernetes/Terraform hardening, final performance/security reports, formal approval chains, real integrations, arbitrary journal administration, partial Reversals, Reconciliation extraction, and applied AI.

## Locked Release 0.3 decisions

- Tenant is the organisation/security boundary and owns one or more permanent Merchants.
- Platform role, Tenant roles, and service identities are separate.
- Keycloak authenticates; Core PostgreSQL authorizes on every protected request.
- Operations Web uses BFF + PKCE + opaque Secure HttpOnly session; Redis is ephemeral session state only.
- Human Tenant selection is explicit per request; service Tenant/Merchant comes from one active credential.
- Manual Payment retry accelerates existing fenced `WAITING_RETRY_REQUEST` work; only the existing consumer creates the next attempt.
- Provider scenario profile/version is pinned at the first Payment/Reversal attempt and reused for every retry/status query.
- Reporting/Notification/SSE are rebuildable projections with stable source-message identities and never transactional truth.
- Reversal is full-only, one per Payment, exact inverse ADR-020, and compensates the original transaction.
- Reconciliation status is Reconciliation-owned, not a Payment field.
- Settlement posting source is stable `SettlementPostingInstruction`, not run-result ID.
- Current-run promotion, settlement posting, and correction lock `BatchFamilyControl` first.
- Reversal settlement requires the corresponding uncompensated Payment settlement.
- Correction exact-compensates only an invalidated `SETTLEMENT_ADJUSTMENT`.
- ADR-025 module/messaging graph is closed and acyclic.
- Merchant webhook secrets are encrypted; endpoint delivery is SSRF-controlled and cannot affect financial state.

## Non-negotiable correctness

Preserve Release 0.1/0.2 rules plus:

- every protected action is Tenant/Merchant scoped and audited where sensitive;
- revoked membership/credential blocks immediately from PostgreSQL truth;
- active Tenant retains at least one active Tenant Admin;
- suspension blocks new user activity but not already committed safety recovery;
- one Reversal and one Reversal-source effect per Payment;
- immutable Reconciliation runs/results and exactly one current pointer;
- stable settlement/correction identities and exact replay;
- no direct cross-module table access;
- no manual normalization of Payment/Reversal postings;
- no long transaction around Keycloak, Provider, MinIO, or merchant webhook HTTP.

## Workflow

Before coding a slice:

1. inspect repository/current evidence and read the active slice;
2. state exact scope, expected files, assumptions, risks, dependencies;
3. use the smallest complete sequence, at most four implementation steps in user guidance;
4. define focused and full verification before coding.

A slice is complete only with domain, persistence, migration, application/API, authorization, failure/recovery, observability, tests, UI where required, and documentation.

Use Java 21, the repository-pinned Spring Boot/Spring Modulith/Gradle versions, PostgreSQL/Flyway, real infrastructure Testcontainers, ArchUnit, and Playwright where approved. Do not use H2.

After implementation report Changed, Verified (exact commands/results), Incomplete, and Deviations.

Do not push, merge, publish, or open a PR unless explicitly requested.
