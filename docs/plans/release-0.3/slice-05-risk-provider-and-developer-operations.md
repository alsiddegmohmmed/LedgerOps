# Release 0.3 Slice 5 - Risk configuration, Provider controls, manual retry, and merchant webhooks

Status: Complete for Slice 5 scope; NTF-01 remains partial by design
Owner: One implementation owner  
Release: 0.3

## Outcome

Tenant-wide Risk configuration is versioned/audited; Platform Admin controls deterministic Provider scenarios; operators can inspect recovery and safely accelerate an existing retry; Integration Developers can test encrypted, signed, SSRF-safe merchant webhooks.

## Authority

RSK-05; PRV-01 through PRV-05; DEV-01 through DEV-03 relevant parts; IAM-04; AUD-01; ADR-018, ADR-021, ADR-022, ADR-025, ADR-026, ADR-027.

## Scope

- Tenant-wide Risk profile/rule management with before/after immutable versions;
- `risk:configuration-manage` valid only with Tenant-wide authority;
- Provider-owned versioned scenario profiles/assignments with GLOBAL/TENANT/PAYMENT precedence, first-attempt pinning, and signed Simulator contract v2;
- Provider attempt/webhook/work/recovery query APIs and UI;
- Provider-owned durable health policy/evaluations/current pointer with exact UNKNOWN/HEALTHY/DEGRADED/UNAVAILABLE boundaries and ProviderHealthChanged;
- manual Payment `retry now` exactly per ADR-026;
- `notification` module foundation for MerchantWebhookEndpoint and delivery work;
- encrypted secret generation/rotation/revocation;
- HTTPS/SSRF/DNS/redirect controls;
- signed synthetic webhook payloads, attempts, retry state, UI, integration reference;
- OpenAPI/JSON Schema/HMAC fixtures.

Excluded:

- general NTF-02 notification preferences;
- Reversal retry;
- real merchant/business webhooks.

## Critical invariants

- Merchant-scoped role cannot change Tenant-wide Risk profile;
- configuration update creates a new version and never rewrites evaluation evidence;
- Provider scenario changes affect only future simulator behavior;
- manual retry accelerates existing fenced `WAITING_RETRY_REQUEST` work; the Provider worker later creates/reuses retryRequestId, and the Payment consumer alone creates the attempt;
- operator/scheduler race converges on one next attempt;
- webhook plaintext secret never persists/logs/exports;
- prohibited network destinations/redirects cannot be reached;
- webhook delivery cannot affect Payment or Ledger state.

## Verification

- Risk config validation, active-version concurrency, rollback, historical reproduction;
- scenario profile validation, precedence, immutable versioning, first-attempt pinning, retry/content-hash consistency, signed v2 fixtures, and audit;
- retry safe/unsafe/exhausted/already-applied matrices and concurrent scheduler/operator test;
- encryption key/version/no-plaintext scans;
- SSRF IPv4/IPv6/DNS rebinding/redirect tests;
- HMAC cross-fixtures, timeout/retry/fenced lease/restart;
- tenant/Merchant endpoint isolation;
- Provider health policy boundary/current/recent/history and state-change-event tests;
- UI Provider scenarios, health, attempts, retry, webhook delivery history;
- full commands.

## Completion report

- Changed:
  - Added Tenant-wide versioned Risk configuration, exact threshold/rule validation, optimistic active-version updates, immutable history reads, audit evidence, authorization checks, and Operations Web management.
  - Added Provider scenario profiles and GLOBAL/TENANT/PAYMENT assignments with immutable versions, precedence resolution, first-attempt canonical pinning, retry/status-query reuse, audit/outbox evidence, and signed Simulator contract v2 compatibility. The existing v1 Simulator contract remains supported.
  - Added Provider health policy, append-only evaluations, current pointer, exact ADR-027 state boundaries, scheduled evaluation, history/current APIs, and `ProviderHealthChanged` state-change events.
  - Added ADR-026 Payment `retry now` as an audited acceleration of an existing safe `WAITING_RETRY_REQUEST`; no attempt, retry request, or Payment/Ledger effect is created by the action.
  - Added the `notification` Merchant webhook foundation: encrypted AES-GCM secrets, one-time create/rotate disclosure, revocation cancellation, HTTPS/SSRF/DNS/redirect controls, canonical HMAC signing, ADR-026-bounded 64 KiB response handling, bounded retry state, delivery attempts, synthetic test events, delivery history, and Operations Web controls.
  - Added Provider work/interaction/recovery/webhook operational projections to the authorized Payment detail API and UI.
  - Added V30/V31 forward-only migrations, provider/notification contract fixtures, Simulator persistence and v2 request handling, focused boundary tests, and the Slice 5 API reference.
- Verified:
  - `GRADLE_USER_HOME=/Users/Siddegx/.gradle ./gradlew :test --console=plain` passed: 621 tests, 0 failures, with the documented PostgreSQL, Kafka, Redis, and Keycloak Compose services healthy.
  - `GRADLE_USER_HOME=/Users/Siddegx/.gradle ./gradlew :check --console=plain` passed.
  - `GRADLE_USER_HOME=/Users/Siddegx/.gradle ./gradlew :applications:provider-simulator:test --console=plain` passed.
  - Operations Web `corepack pnpm exec tsc --noEmit` passed; `corepack pnpm test` passed with 23 tests and 0 failures; `corepack pnpm lint` passed; and `corepack pnpm build` passed. The pinned package still reports the existing Node 22 versus Node 24.18–24.x engine warning in this environment.
  - Provider health boundary, scenario validation/pinning, webhook encryption/URL/retry/64 KiB response bound, Risk persistence, Provider persistence, modularity, and Simulator contract tests passed within the root or focused suites.
  - `git diff --check` passed.
- Incomplete:
  - NTF-01 remains partial by design. Slice 5 supplies webhook delivery infrastructure and does not implement the Notification consumer, in-product notification creation/read state, or target-breach scheduling; those remain Slice 10 work under ADR-027.
  - Reversal retry remains Slice 6 work. Slice 5 implements only Payment retry acceleration.
  - Production business-event producers beyond the synthetic Merchant webhook test events remain excluded by this plan.
  - Authenticated browser Playwright/e2e verification and the documented two-second demo-read measurement were not run in this slice.
- Deviations:
  - None from the approved Slice 5 authority. Global Provider scenario/health outbox envelopes use the existing outbox's required non-null `tenant_id` field with a deterministic system identity; the domain assignments and API payloads remain global/provider-scoped and do not gain Tenant authority.
  - The Simulator keeps the existing v1 request behavior while adding the approved v2 scenario snapshot fields and fixture.
