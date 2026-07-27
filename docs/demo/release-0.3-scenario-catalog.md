# Release 0.3 deterministic scenario catalogue

Each scenario has seeded identities/permissions, launch steps, expected UI/API/event/database evidence, reset instructions, and forbidden outcomes.

## Identity and tenancy

- deterministic initial Platform Admin realm/Core bootstrap;

- initial Tenant onboarding and activation;
- expired/revoked invitation;
- last Tenant Admin protection;
- suspended Tenant historical read/new write denial;
- suspended Merchant credential/Payment denial;
- credential provisioning failure/recovery;
- lost secret response and rotation;
- immediate local credential revocation;
- explicit read-only support session;
- cross-Tenant/Merchant access denial.

## Payment/Risk/Provider

Scenario profiles use the ADR-027 submission/webhook/settlement dimensions, scope precedence, and first-attempt pinning.

- authenticated successful Payment;
- concurrent equivalent idempotent requests;
- cross-Merchant idempotency conflict;
- manual Risk approve/reject/escalate and Case resolution;
- Provider timeout then status-recovered success;
- manual retry-now acceleration racing scheduler;
- duplicate/out-of-order/conflicting Provider webhook;
- merchant webhook success, retry, final failure, rotation, SSRF rejection.

## Reversal

- successful full Reversal and exact compensation;
- duplicate/concurrent Reversal request;
- ambiguous Reversal with status recovery;
- failed Reversal then authorised retry;
- attempt exhaustion and Case path;
- conflicting final Reversal result with no duplicate effect.

## Settlement/Reconciliation/Correction

- exact Payment settlement posting;
- Payment and Reversal settlement in one batch with required order;
- Reversal settlement without Payment settlement discrepancy;
- duplicate file and corrected file version;
- amount/currency/status/date/duplicate/missing/ledger discrepancies;
- immutable rerun with same posting instruction/replay;
- promotion blocked by invalidated uncompensated posting;
- Case-approved exact correction, promotion, replacement posting;
- worker crash/restart with no duplicate rows/effects;
- 100,000-record performance scenario.

## Operations experience

- dashboard metric links to source filters;
- SSE disconnect/stale/reconnect;
- assignment/target-breach notifications;
- audited CSV export with formula-like data;
- English/Arabic/RTL/mixed identifier flow;
- keyboard-only Payment investigation and Case resolution.
