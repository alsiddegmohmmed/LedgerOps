# Release 0.3 plan: Identity and Financial Operations

Status: Active  
Owner: One implementation owner per vertical slice  
Release: 0.3 - Identity and Financial Operations  
Last updated: 2026-07-27

## Outcome

Release 0.3 completes the remaining functional Product `MUST` workflows before Release 1.0 hardening.

At exit:

- every non-public workflow is authenticated and Tenant/Merchant isolated;
- identity, membership, permission, credential, support, and sensitive actions are audited;
- service Payment authority is derived from one active Tenant/Merchant credential;
- operators can explain Payment, Risk, Provider, Ledger, Reversal, Reconciliation, and Case history;
- one full Reversal per Payment is duplicate-safe and atomically compensates ADR-020;
- settlement ingestion and immutable Reconciliation produce stable settlement instructions without rerun duplication;
- Cases control escalation and narrow settlement corrections without arbitrary Ledger mutation;
- Operations Web supports the critical workflows in English and Arabic; and
- Release 0.3 verification proves authorization, recovery, accounting, concurrency, batch, UI, and documentation behavior.

Release 1.0 remains deployment/infrastructure hardening, scanning, performance/resilience reports, public demo evidence, product tour/video, and final portfolio presentation.

## Authority

1. Product Definition v1.7.
2. Technical Specification v1.7.
3. Accepted ADR-022 through ADR-027.
4. Accepted ADR-001 through ADR-021.
5. Completed Release 0.1 and Release 0.2 plans/evidence.

Precedence remains Product, Technical, accepted ADRs, then this plan. A conflict with higher authority stops implementation and follows ADR change control.

## Requirement boundary

Release 0.3 completes remaining `MUST` behavior across:

- TEN-01 through TEN-04;
- IAM-01 through IAM-04;
- PAY-01, PAY-03 through PAY-08;
- RSK-03 through RSK-05;
- PRV-01 through PRV-05;
- LED-03 through LED-05;
- REC-01 through REC-05;
- CAS-01 through CAS-05;
- OPS-01 through OPS-04;
- NTF-01;
- AUD-01 through AUD-04;
- DEV-01 through DEV-04;
- LOC-01 through LOC-03;
- BR-01, BR-03, BR-07 through BR-18.

NTF-02 and NTF-03 remain `SHOULD` and do not block Release 0.3.

## Locked decisions

- Tenant owns one or more Merchants; Merchant never transfers Tenant.
- Platform roles, Tenant roles, and service identities are separate.
- Keycloak authenticates; Core PostgreSQL authorizes.
- Human Tenant selector is explicit and revalidated per request.
- Service Tenant/Merchant comes from one active credential.
- Manual Payment `retry now` accelerates the existing safe retry identity; it never creates an attempt directly.
- Reversal uses one full workflow and exact inverse ADR-020 compensation.
- Reconciliation status is Reconciliation-owned and never written to Payment tables.
- Settlement Ledger source is a stable `SettlementPostingInstruction`, not a run result.
- Current-run promotion, settlement posting, and correction serialize through BatchFamilyControl.
- Casework uses commands/published APIs according to ADR-025; no module cycle.
- Merchant webhook secrets are encrypted and endpoints pass SSRF controls.
- Administration is the cross-module orchestration module; Identity has no business-module dependency; Reporting uses only explicit read APIs/events.
- Provider scenarios are versioned and pinned to the first attempt; product health and projections follow ADR-027.
- Every Payment transition emits one aggregate-versioned `PaymentLifecycleChanged` fact for rebuildable operations views.
- Payment/Reversal postings are never manually corrected.

## Release-wide rules

- One implementation owner changes the active slice's shared contracts, migrations, and transaction boundaries.
- Review/test/document agents may inspect independent concerns in parallel.
- Backend contracts/tests stabilise before corresponding UI work.
- Released Flyway migrations are immutable.
- No H2.
- No cross-module table access.
- Domain code remains free of Spring/JPA/Kafka/HTTP/infrastructure.
- External HTTP/object-storage/Keycloak calls occur outside database transactions.
- Every sensitive action has current authorization, confirmation/reason where required, and immutable audit.
- Every financial effect has stable source identity, exact replay, database uniqueness, concurrency tests, and rollback tests.
- Release 1.0 infrastructure and applied AI remain excluded.

## Ordered slices

| Slice | Outcome | Plan |
|---|---|---|
| 0 | Approved/reconciled Release 0.3 baseline in repository, including ADR-027 | [Slice 0](release-0.3/slice-00-documentation-and-authorization.md) |
| 1 | Identity, Audit, authenticated context, and minimal BFF | [Slice 1](release-0.3/slice-01-identity-audit-and-request-context.md) |
| 2 | Tenant/Merchant onboarding, membership, credentials, and support | [Slice 2](release-0.3/slice-02-tenant-membership-credentials-and-support.md) |
| 3 | Payment/Ledger search, detail, timeline, notes, and audit search | [Slice 3](release-0.3/slice-03-payment-ledger-operations.md) |
| 4 | Casework foundation and complete manual Risk workflow | [Slice 4](release-0.3/slice-04-casework-and-manual-risk.md) |
| 5 | Risk configuration, Provider controls, manual retry, and merchant webhooks | [Slice 5](release-0.3/slice-05-risk-provider-and-developer-operations.md) |
| 6 | Full-payment Reversal | [Slice 6](release-0.3/slice-06-full-payment-reversal.md) |
| 7 | Settlement generation, upload, validation, and durable ingestion | [Slice 7](release-0.3/slice-07-settlement-ingestion.md) |
| 8 | Immutable Reconciliation, current-run promotion, and settlement posting | [Slice 8](release-0.3/slice-08-reconciliation-and-settlement-posting.md) |
| 9 | Controlled settlement corrections and complete Case resolution | [Slice 9](release-0.3/slice-09-controlled-corrections.md) |
| 10 | Dashboard, SSE, notifications, reports, localisation, and scenarios | [Slice 10](release-0.3/slice-10-operational-experience.md) |
| 11 | Release 0.3 verification gate | [Slice 11](release-0.3/slice-11-release-gate.md) |

## Dependency order

```text
0 -> 1 -> 2 -> 3 -> 4 -> 5 -> 6 -> 7 -> 8 -> 9 -> 10 -> 11
```

The order is intentional. Later slices consume authorization, audit, query, Casework, Provider, Reversal, or Reconciliation contracts introduced earlier.

## Verification baseline

Every slice starts with focused inspection and ends with narrow tests plus:

```bash
./gradlew test --console=plain
./gradlew check --console=plain
git diff --check
```

Frontend slices also run from `applications/operations-web`:

```bash
pnpm lint
pnpm typecheck
pnpm test
pnpm exec playwright test
pnpm build
```

Versions are pinned in the repository when Slice 1 creates the frontend; the plan does not float unpinned dependencies.

## Release gate

Release 0.3 is complete only when:

- authorization and Tenant/Merchant negative matrices pass;
- last-Tenant-Admin and immediate credential revocation invariants pass;
- manual Payment retry converges with the scheduler on one retry identity;
- one Reversal and one exact compensation survive duplicate/concurrent/failpoint tests;
- ingestion is streaming, immutable, restartable, and duplicate-safe;
- current-run promotion cannot race with posting/correction;
- settlement instruction/replay and Reversal settlement ordering pass;
- correction is narrow, exact, and one-per-target;
- Case closure and Risk escalation effects are complete;
- dashboard/report/export totals reconcile to source facts;
- critical English/Arabic and accessibility workflows pass;
- all contracts, migrations, docs, runbooks, and traceability match executable evidence;
- no Release 1.0 or AI capability entered production.
