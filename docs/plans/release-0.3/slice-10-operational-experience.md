# Release 0.3 Slice 10 - Operational experience, notifications, reporting, localisation, and scenarios

Status: In progress — operational summary contract approved; implementation underway
Owner: One implementation owner  
Release: 0.3

## Outcome

The complete Operations Web presents actionable, live, bilingual, accessible workflows. Notifications, reports, exports, scenario launcher, and seeded data remain derived, scoped, auditable, and consistent with source facts.

## Authority

OPS-01 through OPS-04; NTF-01; AUD-03/04; DEV-01 through DEV-04; LOC-01 through LOC-03; ADR-022, ADR-025, ADR-026.

The approved Slice 10 operational-summary and Reporting SSE API contracts are documented in
[`docs/api/release-0.3-contract-plan.md`](../../api/release-0.3-contract-plan.md).
It defines the Reporting summary resource, the keyset-paginated drill-down,
metric formulas, source links, Merchant scope behavior, projection
generation, `REPORTING_NOT_READY` behavior, and the Tenant-scoped invalidation
stream. No new ADR is required.

The current implementation slice adds source-owned read boundaries, a
Reporting rebuild service, the approved operational-summary API, and the
persisted invalidation stream with its same-origin Operations Web bridge. The
rebuild reads Payment, Risk, Casework, Reconciliation, and Provider facts,
composes one complete Tenant fact set, and then atomically switches a new
Reporting generation. The approved SSE contract uses persisted Tenant event
IDs and invalidation-only payloads; it does not send business records or
expose Core bearer tokens to the browser. This work does not add a public
rebuild endpoint, query source tables from Reporting, call an external
Provider, implement public Notification creation/read APIs, or mark Slice 10
complete.

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

The approved SSE contract requires:

- `GET /api/v1/tenants/{tenantId}/reports/events` with `text/event-stream`;
- repeatable `merchantId` filters using the operational-summary authorization rules;
- monotonic `id` values and `Last-Event-ID` replay;
- `projection-updated` invalidation signals with `generation`, `affected`, and
  `occurredAt`, not complete business records;
- the current operational-summary publisher emits `OPERATIONAL_SUMMARY` only;
  other affected values require their own implemented Reporting publisher;
- `resync-required` with `CURSOR_UNAVAILABLE` when the cursor is unavailable;
- a 15-second `: keepalive` heartbeat and `retry: 3000`;
- same-origin BFF streaming so the Core bearer token remains server-side;
- snapshot-first connection and Tenant/Merchant filter switching semantics.

## Verification

- projection duplicate/reorder/rebuild;
- dashboard totals against the same-filter drill-down and authoritative source queries;
- operational-summary period boundaries, currency grouping, null zero-denominator rates, and Merchant-scope denial;
- operational-summary keyset pagination, metric/source-link equality, and complete-generation switching;
- rebuild source-boundary composition, current-run discrepancy filtering, and
  no-partial-generation behavior;
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
