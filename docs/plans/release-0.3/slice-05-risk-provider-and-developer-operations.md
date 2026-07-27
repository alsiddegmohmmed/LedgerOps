# Release 0.3 Slice 5 - Risk configuration, Provider controls, manual retry, and merchant webhooks

Status: Pending  
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

- Changed: Pending
- Verified: Pending
- Incomplete: Slice 5
- Deviations: None
