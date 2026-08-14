# Release 0.3 Slice 4 - Casework foundation and manual Risk workflow

Status: Complete for Slice 4 scope; NTF-01 partial by design
Owner: One implementation owner  
Release: 0.3

## Outcome

Risk-review items are assignable and decidable. Escalation requests one Case without creating a module cycle. Case resolution can atomically apply the existing Payment transition through a focused Payment API.

## Authority

RSK-03, RSK-04; CAS-01 through CAS-05 foundation; NTF-01; AUD-01; BR-07 through BR-09/11/12; ADR-022, ADR-025.

## Exact lifecycles

RiskReview status:

```text
UNASSIGNED -> ASSIGNED
ASSIGNED   -> DECIDED | ESCALATED
```

Human decision is exactly `APPROVE | REJECT | ESCALATE` and is final for the RiskReview.

Case status and transitions remain exactly the Product baseline:

```text
OPEN                 -> INVESTIGATING | AWAITING_INFORMATION
AWAITING_INFORMATION -> INVESTIGATING
INVESTIGATING        -> RESOLVED
RESOLVED             -> CLOSED | INVESTIGATING
CLOSED               -> REOPENED
REOPENED              -> INVESTIGATING
```

Every transition is timestamped and attributed. Reopening requires a reason and preserves prior resolution/closure history. Case/discrepancy severity is exactly `CRITICAL | HIGH | MEDIUM | LOW`.

## Exact case categories and resolutions

Case source category is exactly `RISK_REVIEW | RECONCILIATION_DISCREPANCY`.

Risk-case resolution is exactly `RISK_APPROVE | RISK_REJECT`. Reconciliation-case resolution is exactly `PROVIDER_ERROR | INTERNAL_PROCESSING_ERROR | DUPLICATE_EXTERNAL_RECORD | EXPECTED_TIMING_DIFFERENCE | APPROVED_CORRECTION | FALSE_POSITIVE`. `APPROVED_CORRECTION` is unavailable until Slice 9 supplies a completed CorrectionRequest.

## Approved initial operational policies

- The initial RiskReview SLA is 24 hours: `dueAt = createdAt + 24 hours`.
- Assignment and reassignment do not reset or extend the RiskReview SLA.
- A RiskReview escalation deterministically creates a `HIGH` Case. Ordinary Risk escalation does not create a `CRITICAL` Case.
- Slice 4 emits Risk and Case lifecycle facts containing assignment and due-time information. The Notification consumer, notification creation, target-breach scheduling, and read state are deferred to Slice 10 under ADR-027; NTF-01 is therefore partial in Slice 4.

## Scope

- `casework` module/schema and source-reference uniqueness;
- Case assignment, severity, due time, notes/evidence, history, resolution category, close/reopen;
- RiskReview aggregate unique by Tenant + Payment; migration/backfill from existing immutable MANUAL_REVIEW evaluations; future MANUAL_REVIEW decisions create it atomically;
- manual Risk queue, assignment/reassignment, priority, SLA age;
- Payment-owned approve/reject orchestration with Risk/Audit transaction;
- Payment-owned ESCALATE transaction locks Payment then RiskReview, records the final decision, allocates one stable case ID, and appends `CreateCaseRequested`; Payment remains `RISK_REVIEW`;
- Casework command consumer creates exactly one Case;
- controlled `RISK_APPROVE | RISK_REJECT` resolution calls `payment::api` and atomically transitions Payment, resolves Case, writes audit/outbox;
- Risk/Case UI and the lifecycle facts required by future assignment notifications; Notification consumption and read state remain partial by explicit current-scope decision.

Excluded:

- reconciliation discrepancy sources;
- financial CorrectionRequest;
- Risk rule configuration.

## Critical invariants

- one RiskReview per Payment and one final human decision per RiskReview;
- only currently assigned/authorised analyst decides;
- approve/reject use only existing Payment transitions;
- escalation creates one Case per review and no direct Payment->Casework dependency;
- Case cannot resolve Risk escalation without applying Payment transition;
- Case cannot close without resolution category/note/required effect;
- concurrent assignment/decision/resolution produces one effect.

## Verification

- domain/property lifecycle tests;
- coordinated decision and case-command duplicate tests;
- outbox/inbox crash recovery;
- Payment/Risk/Case/Audit rollback at every failpoint;
- Casework->Payment published API and no reverse dependency;
- SLA injected Clock boundaries;
- UI assignment/decision/close/reopen and permission denial;
- full commands.

## Completion report

- Changed:
  - Added the RiskReview aggregate, exact manual-review lifecycle, assignment/decision API, 24-hour initial SLA policy, migration/backfill, queue persistence, lifecycle facts, and Payment-owned approve/reject/escalate orchestration.
  - Added the Casework module, exact Case lifecycle, assignment/notes/history/resolution/close/reopen behavior, source-reference uniqueness, append-only evidence, idempotent Case creation consumer, and focused Payment resolution API.
  - Added Risk Review and Casework HTTP APIs, tenant/merchant authorization boundaries, Operations Web queues/actions, and BFF routes.
  - Added the deterministic `RISK_REVIEW -> HIGH` severity mapping and explicit injected-Clock SLA boundary/reassignment tests.
- Verified:
  - `GRADLE_USER_HOME=/Users/Siddegx/.ledgerops-slice-4-gradle ./gradlew :test --console=plain` passed.
  - `GRADLE_USER_HOME=/Users/Siddegx/.ledgerops-slice-4-gradle ./gradlew check --console=plain` passed.
  - Focused RiskReview/Casework domain, modularity, and architecture tests passed.
  - Operations Web `corepack pnpm typecheck` passed.
  - Operations Web `corepack pnpm test` passed: 23 tests, 0 failures.
  - Operations Web `pnpm build` passed under the required Node 24.18.0 runtime. Earlier Node 22 output is historical and is not final evidence.
  - `git diff --check` passed.
- Incomplete:
  - NTF-01 is intentionally partial: the Notification consumer, notification creation/read state, and target-breach scheduling belong to Slice 10 under ADR-027.
  - Reconciliation discrepancy source integration and financial CorrectionRequest remain deferred as specified.
  - Browser Playwright/e2e verification and the documented two-second demo-read target were not measured in this slice.
- Deviations:
  - None from the approved Slice 4 authority. The 24-hour SLA and `HIGH` Risk-escalation severity are approved initial operational policies, not new ADR-level architecture decisions.
