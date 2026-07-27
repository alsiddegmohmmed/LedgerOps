# Release 0.3 architecture closure review

Status: Passed - implementation authorised  
Review date: 2026-07-27  
Reviewed authority: Product v1.6, Technical v1.6, ADR-016 through ADR-021, retrospective ADR authority, completed Release 0.1/0.2 evidence, current repository model, and final Product/Technical v1.7 package.

## Method

The review did not patch isolated wording. It evaluated every Release 0.3 `MUST` requirement against:

- product meaning and acceptance;
- module/data owner;
- lifecycle and terminal state;
- transaction boundary;
- stable identity/idempotency/concurrency;
- authorization/Tenant/Merchant scope;
- external failure/recovery;
- API/event contract;
- verification evidence;
- ordered implementation slice.

The result is recorded in `docs/requirements/release-0.3-decision-matrix.md`.

## Closed conflicts

| Conflict/gap | Resolution |
|---|---|
| one Tenant/one Merchant vs Merchant scopes/current schema | Tenant is organisation/security boundary; owns one or more permanent Merchants (ADR-022). |
| Product/Technical role mismatch | separate Platform role, Tenant role catalogue, service identity; explicit scope mode (ADR-022). |
| initial Merchant Admin ambiguity | initial Tenant Admin plus initial Merchant; explicit Platform activation prerequisites (ADR-022). |
| credential/Keycloak non-atomicity | deterministic durable provisioning, exact transitions, local-first revocation, one-time disclosure (ADR-022). |
| browser active Tenant/session ambiguity | explicit route selector; per-request PostgreSQL authority; BFF session is not authority (ADR-022). |
| Reversal accounts/source/replay | exact inverse ADR-020, REVERSAL source, original compensation link, atomic completion (ADR-023). |
| `REVERSAL_PAYABLE` role | reserved for future staged workflow; unused in Release 0.3 (ADR-023). |
| Reversal attempts | typed Payment Attempt subject; no ReversalAttempt (ADR-023). |
| settlement source tied to rerun result | stable SettlementPostingInstruction identity (ADR-024). |
| current-run promotion/posting/correction race | BatchFamilyControl first lock and promotion blocking (ADR-024). |
| Reversal settlement without Payment settlement | explicit discrepancy/no posting; Payment settlement must exist first (ADR-024). |
| ReconciliationStatus ownership | Reconciliation-owned subject history/projection; no Payment-table mutation (ADR-024). |
| arbitrary correction | exact inverse of invalidated SETTLEMENT_ADJUSTMENT only (ADR-024). |
| new module cycles and Identity/onboarding cycle | Administration owns cross-module orchestration; Identity has no business dependencies; Case commands/focused APIs preserve the locked acyclic graph (ADR-025). |
| Release 0.2 closed producer catalogue | exact Release 0.3 producers/topics/messages/dedup/partition keys, nonterminal Reversal lifecycle identities, and Provider-health events (ADR-025). |
| Risk escalation unfinished | final ESCALATE decision, one Case, Casework-owned atomic Payment resolution while preserving the Product Case lifecycle (ADR-025). |
| manual Payment retry race | accelerate existing fenced WAITING_RETRY_REQUEST work; the Provider worker creates the stable retry request and the Payment consumer alone creates the attempt (ADR-026). |
| merchant webhook secret unusable as hash-only | authenticated encryption with external master key, one-time display, rotation (ADR-026). |
| merchant webhook SSRF | HTTPS/network/DNS/redirect controls and local-only exception profile (ADR-026). |
| Tenant-wide Risk configuration after multi-Merchant change | Tenant-wide permission only; Merchant Admin cannot mutate Tenant profile (Product/Technical v1.7). |
| Platform Admin within Tenant roles | separate Platform assignment; support read-only and explicit (ADR-022). |
| last active Tenant Admin | protected invariant (ADR-022). |
| suspension vs in-flight correctness | new user activity blocked; committed idempotent recovery continues (ADR-022). |
| direct permission grants increased scope/ambiguity | removed; exact closed role-permission-scope matrix and machine `payment:create` only (ADR-022). |
| Provider scenario configuration could change retry content | versioned profiles resolve by Payment/Tenant/Global precedence and pin at first attempt; signed v2 request reuses snapshot (ADR-027). |
| Prometheus could become product-health truth | Provider-owned durable policy/evaluation/current pointer; metrics are derivative (ADR-027). |
| incomplete Payment lifecycle/timeline facts | aggregate-versioned PaymentLifecycleChanged, honest baseline marker, source-message-id projections (ADR-027). |
| settlement row identity could either collapse duplicates or duplicate unchanged corrected-file postings | canonical record version reuses unchanged content while per-file occurrences preserve every duplicate/conflict; instructions key the canonical version (ADR-024). |
| settlement file format/normalization could drift by implementation | exact versioned CSV header, limits, validation, dual identities, deterministic matching, and posting relationship are locked in `docs/api/provider-settlement-file-v1.md`. |
| settlement instruction had no restart application state | one PENDING/POSTED application, current-run revalidation, exact local transaction, separate failure evidence (ADR-024). |

## Compatibility with existing repository

- Existing Merchant has Tenant ownership and `ACTIVE | SUSPENDED`, compatible with ADR-022.
- Existing Payment idempotency remains Tenant-wide and Merchant-aware, unchanged.
- Existing Payment Attempt is extended additively with a typed subject and backfill.
- Existing Ledger already supports `REVERSAL`, `SETTLEMENT_ADJUSTMENT`, `AUTHORISED_CORRECTION`, and compensation references.
- ADR-020 and ADR-021 remain unchanged for Payment success and Release 0.2 delivery.
- All schema changes are forward Flyway migrations; released migrations remain immutable.

## Gates passed in documentation

- Every listed Release 0.3 `MUST` requirement has owner, identity/state/transaction/security/recovery/test/slice mapping.
- Module graph is acyclic, including the Administration/Identity authorization direction.
- Every financial effect has stable identity and exact replay.
- Every external dependency has an outside-transaction recovery model.
- Sensitive actions are permissioned/audited.
- Release 1.0 and AI scope remain excluded.
- All slices have prerequisites, exclusions, acceptance, failure, and verification.

## Residual implementation risks (not unresolved decisions)

These are expected engineering risks and are covered by tests/runbooks rather than more product decisions:

- Keycloak/Admin API behavior and container configuration at the pinned version;
- performance of per-request authorization and large Reconciliation runs;
- lock contention in batch-family financial operations;
- frontend accessibility/RTL defects;
- object-storage/network failures;
- schema migration defects.

A risk becomes an ADR issue only if implementation evidence requires changing an approved boundary.

## Conclusion

No unresolved Product/Technical/architecture conflict remains that blocks Release 0.3 Slice 1. The baseline is implementation-authorised. This does not claim that implementation cannot reveal new evidence; it requires the contradiction gate if it does.
