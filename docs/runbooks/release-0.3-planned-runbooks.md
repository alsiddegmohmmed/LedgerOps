# Release 0.3 runbook plan

Each listed runbook must exist before its owning slice completes.

## Identity and authorization

- Platform Admin bootstrap mismatch or duplicate mapping.
- Keycloak unavailable during login.
- Client provisioning stuck `PROVISIONING` or `FAILED`.
- Secret response lost; rotate without redisplay.
- Local credential revoked while token remains valid.
- BFF/Redis session outage and safe reauthentication.
- Tenant/Merchant suspension and committed recovery behavior.
- Suspected cross-Tenant authorization incident.
- Support-session expiry/revocation.

## Provider and merchant webhooks

- Manual retry denied/accelerated/already applied.
- Provider ambiguity/status-recovery exhaustion.
- Merchant webhook DNS/SSRF rejection.
- Merchant webhook retry backlog/final failure/key rotation.

## Reversal

- Reversal stuck `PROCESSING`.
- Definitive failed Reversal and safe retry evidence.
- Reversal success conflict or missing original Payment posting.
- Reversal/Ledger atomic rollback verification.

## Settlement and Reconciliation

- MinIO upload succeeded but metadata failed.
- Duplicate/corrected batch identification.
- Settlement-file contract/header/version rejection.
- Duplicate/conflicting physical row and canonical-version investigation.
- Spring Batch job crash/restart.
- Candidate run blocked from promotion by uncompensated posting.
- Current-run pointer/posting contention.
- Reversal settlement without Payment settlement.
- Low match rate/large discrepancy volume.

## Casework and corrections

- Duplicate Case command.
- Risk escalation waiting for Case/Payment effect.
- Correction failed before/after Ledger call.
- Promotion/correction lock contention.
- Payment/Reversal posting inconsistency: investigate, never normalise.

## Operations Web/reporting

- SSE stale/disconnected/reconnect.
- Projection lag/rebuild.
- Notification backlog.
- Report/export mismatch or CSV safety incident.
- Arabic/RTL/accessibility regression.

Every runbook includes impact, diagnosis, read-only evidence queries, safe action, forbidden action, recovery signal, and correctness verification. No runbook instructs direct table mutation or arbitrary replay.


## Provider scenario and health

- invalid/superseded scenario assignment;
- pinned scenario inspection for an in-flight Payment/Reversal;
- Provider health UNKNOWN/DEGRADED/UNAVAILABLE diagnosis from durable evidence;
- projection/ProviderHealthChanged backlog.

## Reporting, notification, and SSE

- projection rebuild from source facts;
- duplicate/poison consumer handling;
- SSE cursor unavailable/resync;
- stale notification authority and target-breach scheduler recovery.
