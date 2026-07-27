# Release 0.3 Slice 4 - Casework foundation and manual Risk workflow

Status: Pending  
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

## Scope

- `casework` module/schema and source-reference uniqueness;
- Case assignment, severity, due time, notes/evidence, history, resolution category, close/reopen;
- RiskReview aggregate unique by Tenant + Payment; migration/backfill from existing immutable MANUAL_REVIEW evaluations; future MANUAL_REVIEW decisions create it atomically;
- manual Risk queue, assignment/reassignment, priority, SLA age;
- Payment-owned approve/reject orchestration with Risk/Audit transaction;
- Payment-owned ESCALATE transaction locks Payment then RiskReview, records the final decision, allocates one stable case ID, and appends `CreateCaseRequested`; Payment remains `RISK_REVIEW`;
- Casework command consumer creates exactly one Case;
- controlled `RISK_APPROVE | RISK_REJECT` resolution calls `payment::api` and atomically transitions Payment, resolves Case, writes audit/outbox;
- Risk/Case UI and assignment notifications.

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

- Changed: Pending
- Verified: Pending
- Incomplete: Slice 4
- Deviations: None
