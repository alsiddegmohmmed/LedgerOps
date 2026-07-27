# Release 0.3 Slice 11 - Verification and release gate

Status: Pending  
Owner: Release verification owner  
Release: 0.3

## Outcome

No new product behavior is added. All Release 0.3 claims are proven, documents match implementation, and Release 1.0 may start.

## Scope

- full backend/frontend/tests/contracts/migrations/local topology;
- requirement decision matrix updated from Planned to exact evidence;
- authorization/Tenant/Merchant negative matrix;
- Keycloak/Redis/Kafka/PostgreSQL/MinIO/Provider/browser end-to-end;
- Reversal accounting/replay/concurrency/failpoint gate;
- ingestion/reconciliation/promotion/posting/correction restart/concurrency gate;
- Case/Risk/notification/report/localisation/accessibility gate;
- observability, alerts, runbooks, reset, known limitations;
- terminology/link/DOCX render/diff review;
- repository clean-scope review and release tag recommendation.

## Required commands

```bash
./gradlew clean test --console=plain --stacktrace
./gradlew check --console=plain --stacktrace
git diff --check
```

From `applications/operations-web`:

```bash
pnpm install --frozen-lockfile
pnpm lint
pnpm typecheck
pnpm test
pnpm exec playwright test
pnpm build
```

Also execute:

- empty PostgreSQL install and upgrade from the Release 0.2 schema;
- contract fixture validation for every JSON Schema/HMAC contract;
- 100,000-record ingestion/reconciliation evidence;
- local topology/demo from clean reset;
- dependency/secret/vulnerability checks available in the repository without introducing Release 1.0 deployment scope.

## Release blockers

- cross-Tenant/Merchant disclosure or authorization bypass;
- last-admin or immediate-revocation failure;
- duplicate Payment/Reversal/settlement/correction financial effect;
- unbalanced/wrong-template posting;
- current-run/posting/correction race;
- Reconciliation direct mutation of another module;
- unsafe manual retry or SSRF-capable webhook;
- unrecoverable migration/batch/provisioning state;
- English/Arabic functional mismatch in a critical workflow;
- inaccessible critical workflow;
- documentation claiming evidence that does not exist.

## Completion report

- Changed: Pending
- Verified: Pending
- Incomplete: Slice 11
- Deviations: None
