# Release 0.3 Slice 10 - Operational experience, notifications, reporting, localisation, and scenarios

Status: Pending  
Owner: One implementation owner  
Release: 0.3

## Outcome

The complete Operations Web presents actionable, live, bilingual, accessible workflows. Notifications, reports, exports, scenario launcher, and seeded data remain derived, scoped, auditable, and consistent with source facts.

## Authority

OPS-01 through OPS-04; NTF-01; AUD-03/04; DEV-01 through DEV-04; LOC-01 through LOC-03; ADR-022, ADR-025, ADR-026.

## Scope

- Reporting projections/queries and rebuild procedure;
- operational dashboard with linked filtered records;
- SSE per authorised Tenant/Merchant context, last-event/reconnect/stale state;
- in-product notification creation/read state from lifecycle consumers;
- payment/risk/provider/reconciliation/case/ledger reports;
- Tenant-scoped audited CSV export, bounded rows, formula-injection protection;
- full OpenAPI/auth/idempotency/HMAC/webhook developer docs;
- scenario launcher/reset/expected evidence;
- realistic synthetic users, Payments, attempts, Cases, batches, incidents;
- English/Arabic message/status/navigation parity;
- RTL/LTR/mixed identifier layout;
- locale/timezone/amount formatting;
- keyboard/focus/semantic labels/contrast/non-colour cues;
- complete UI extensions for prior slices.

Excluded:

- NTF-02/03 general external notification preferences unless already delivered without delaying gate;
- public AWS/Kubernetes deployment;
- applied AI.

## Critical invariants

- report/projection/notification/SSE state is never transactional truth;
- every dashboard metric links to matching source filters;
- SSE cannot bypass authorization and shows stale/disconnected status;
- notification recipients are resolved from current authority;
- exports exclude secrets and neutralize spreadsheet formulas;
- API status codes remain stable English codes; UI translations preserve meaning;
- Arabic changes layout only, not capability/authorization.

## Verification

- projection duplicate/reorder/rebuild;
- dashboard totals against source queries;
- SSE disconnect/reconnect/last-event/tenant switch;
- notification role/scope/read-state;
- report/export totals, limits, audit, formula injection;
- scenario deterministic reset and expected final rows;
- bilingual terminology snapshot and no untranslated primary workflow;
- Playwright LTR/RTL/mixed content;
- automated accessibility plus manual keyboard checks;
- developer completes payment/webhook flow from docs only;
- full commands.

## Completion report

- Changed: Pending
- Verified: Pending
- Incomplete: Slice 10
- Deviations: None
