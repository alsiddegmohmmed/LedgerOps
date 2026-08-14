# Release 0.3 Slice 11 - Verification and release gate

Status: Verification run completed; manual accessibility review explicitly deferred; final scope closure pending
Owner: Release verification owner  
Release: 0.3

## Outcome

No new domain behavior is added. This gate records the verified Release 0.3
evidence and keeps the release open until the remaining clean-scope and
documentation checks are explicitly closed. Manual keyboard/accessibility
review is an explicit user-approved deferral, not an active workflow step.

## Scope

- full backend/frontend/tests/contracts/migrations/local topology;
- requirement decision matrix updated from Planned to exact evidence;
- authorization/Tenant/Merchant negative matrix;
- Keycloak/Redis/Kafka/PostgreSQL/MinIO/Provider/browser end-to-end;
- Reversal accounting/replay/concurrency/failpoint gate;
- ingestion/reconciliation/promotion/posting/correction restart/concurrency gate;
- Case/Risk/notification/report/localisation gate plus automated accessibility
  checks; manual keyboard/accessibility review is explicitly deferred;
- observability, alerts, runbooks, reset, known limitations;
- terminology/link/DOCX render/diff review;
- repository clean-scope review and release tag recommendation.

## Required commands

```bash
./gradlew clean test --console=plain --stacktrace
./gradlew check --console=plain --stacktrace
./gradlew releaseScaleTest --console=plain --stacktrace
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
pnpm audit --prod --audit-level high
```

The frontend gate must use Node `24.18.x`, as required by the Operations Web
package engine range. The shared Playwright fixture is serial and the
slice-specific suites are run explicitly when their seed profile is required:

```bash
pnpm exec playwright test --config=playwright.slice6.config.ts
pnpm exec playwright test --config=playwright.slice9.config.ts
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

- Changed: The gate adds the bounded `Release03ScaleEvidenceTests` and dedicated `releaseScaleTest` task, corrects the valid MinIO image tag used by the local Release 0.3 topology, records the tested demo rebuild command, and updates the Operations Web dependency set to Next 16.3.1 with a narrow `nanoid` security override. It adds no domain behavior.
- Verified: `./gradlew clean test --console=plain --stacktrace` completed with 740 tests, 0 failures, 0 errors, and 0 skipped; `./gradlew check --console=plain --stacktrace` passed; `./gradlew releaseScaleTest --console=plain --stacktrace` passed both 100,000-record bounded ingestion/reconciliation tests; and `git diff --check` passed. The focused `com.ledgerops.contracts.*` fixture suite passed, the Provider Simulator HMAC compatibility fixture passed, and `Release03MigrationUpgradeIntegrationTests` passed: fresh V1–V45 install plus V14-to-V45 upgrade with Tenant preservation. The merged Release 0.2/0.3 Compose configuration passes `docker compose ... config --quiet`; the existing `ledgerops` topology reached healthy Redis, Keycloak, MinIO, Prometheus, Grafana, PostgreSQL, and simulator-PostgreSQL services; the guarded demo seed and source-boundary Reporting rebuild completed successfully. Under Node 24.18.0, `pnpm install --frozen-lockfile`, `pnpm lint` (one existing Next navigation warning, no errors), `pnpm typecheck`, `pnpm test` (37 tests), `pnpm build`, the default Playwright suite (10 passed), the Slice 6 suite (1 passed), the Slice 9 suite (1 passed), and `pnpm audit --prod --audit-level high` (no known vulnerabilities) passed. The tracked high-confidence secret scan passed.
- Incomplete: A destructive clean-reset database run was intentionally not performed because the existing local Core PostgreSQL volume was preserved; topology and demo evidence were verified against that preserved data. The final clean-scope/documentation review remains before release closure. Manual keyboard/accessibility review is explicitly deferred by the user; automated browser and semantic UI checks passed, but this does not constitute manual accessibility evidence. NTF-01 notification read state and Arabic/RTL localization remain explicit project deferrals.
- Deviations: An initial full-suite Keycloak Testcontainers startup timed out while the container was still initializing; the isolated five-test Keycloak class and the subsequent full suite passed, so the initial result is classified as environment startup timing rather than a product failure. Earlier Node 22 frontend output is discarded as non-final evidence; the current frontend evidence uses Node 24.18.0. Manual accessibility review is a documented Release 0.3 verification deferral by explicit user decision. The working tree is not a clean release snapshot and no release commit/tag was created.
