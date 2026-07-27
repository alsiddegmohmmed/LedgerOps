# ADR-026: Define Release 0.3 manual Payment retry and sandbox merchant-webhook semantics

Status: Accepted  
Date: 2026-07-27  
Decision owners: Product owner; Architecture owner  
Supersedes: Unspecified manual retry and merchant webhook details in Product/Technical v1.6  
Superseded by: None

## Context

PRV-04 requires visible manual retry safety. DEV-02 requires an Integration Developer to configure a sandbox webhook endpoint and inspect delivery/retry state.

Release 0.2 already owns automatic Payment retry through durable Provider evidence and a stable retry request. A new operator path must not bypass that identity or the three-attempt limit.

Outbound merchant webhooks require signing secret availability. “Show once” cannot mean the secret is discarded, because LedgerOps must sign later deliveries. Storing plaintext or an irreversible hash would be unusable; storing plaintext would be unsafe. User-supplied endpoints also create SSRF risk.

## Decision

### Manual Payment retry

The Release 0.3 Payment action is `retry now`, not “create another attempt”.

It is enabled only when Provider owns `WAITING_RETRY_REQUEST` work backed by durable `SAFE_TO_RESUBMIT` evidence for the Payment, the Payment remains `PROCESSING`, the attempt limit has not been reached, and no retry request/command/next attempt has already been applied.

The action:

- requires `payment:retry`, current Tenant/Merchant scope, confirmation, reason, and audit;
- calls a focused `provider::api` operation;
- reuses the existing Provider work and evidence identity;
- moves the `WAITING_RETRY_REQUEST` due time to `min(currentDueAt, now)`;
- creates no Payment Attempt, retry request, or outbox message directly; and
- is idempotent under operator/scheduler races.

The existing fenced Provider worker remains the only component that creates the stable `retryRequestId` and `PaymentSubmissionRetryRequested` outbox record when the work becomes due. The existing Payment retry-command consumer remains the only component that creates the next Payment Attempt and `SubmitPaymentToProvider` outbox record. The ADR-021 maximum of three total Payment Attempts remains unchanged.

If the durable state is ambiguous, exhausted, already applied, or not safe, the API returns a typed state/conflict result and creates no effect.

### Merchant webhook ownership

The `notification` module owns sandbox merchant webhook endpoints, encrypted signing secrets, delivery work, attempts, retry state, and metrics.

This boundary is distinct from Provider Simulator-to-Core webhooks.

### Endpoint model

Each endpoint belongs to one Tenant and one Merchant and has:

- endpoint ID and label;
- HTTPS URL;
- status `ACTIVE | REVOKED`;
- encrypted HMAC secret and key version;
- created/rotated/revoked audit evidence;
- allowed synthetic event types.

Secret plaintext is generated and shown once. It is encrypted with authenticated encryption before persistence. The master key is supplied through environment/secret management and is never stored in the database. Ciphertext, nonce, algorithm/version, and key version are stored. Plaintext is never logged, exported, or returned again.

Rotation creates a new encrypted secret/version and uses it for newly created delivery events. Already committed delivery work retains its captured secret version through terminal success/failure; old encrypted material may be destroyed only after no nonterminal work references it. Revocation blocks new events, cancels unclaimed work, and prevents further retries. A worker rechecks endpoint status immediately before each HTTP call; an HTTP request already in flight cannot be recalled, but its result creates no retry after revocation.

### SSRF and URL policy

Production-like profiles permit HTTPS only and reject:

- loopback, link-local, private, multicast, and metadata-network addresses;
- URLs with embedded credentials, query strings, or fragments;
- non-standard schemes;
- redirects to a prohibited address;
- DNS resolution that changes to a prohibited address at connection time.

DNS is resolved and revalidated for every attempt. Redirects are disabled by default. A local-development profile may explicitly allow localhost and is never enabled in shared/public environments.

### Delivery contract

Webhook payloads are versioned JSON and contain synthetic, Tenant/Merchant-scoped demonstration data only. The closed Release 0.3 test-event types are:

```text
payment.completed
payment.failed
payment.reversed
risk.review.required
reconciliation.discrepancy.created
```

Required headers are `Content-Type`, `X-LedgerOps-Webhook-Id`, `X-LedgerOps-Webhook-Key-Version`, `X-LedgerOps-Webhook-Timestamp`, `X-LedgerOps-Webhook-Event-Id`, and `X-LedgerOps-Webhook-Signature`. Canonical signing bytes are UTF-8 with ASCII LF and no trailing LF:

```text
v1
POST
<RAW_PATH_WITHOUT_QUERY>
<WEBHOOK_ENDPOINT_ID>
<KEY_VERSION>
<TIMESTAMP_DECIMAL>
<EVENT_ID>
<LOWERCASE_SHA256_RAW_BODY>
```

HMAC-SHA256 is transmitted as `v1=<base64url-without-padding MAC>`. Golden fixtures are published for integrators.

A delivery event has one stable business identity and one durable work item. Duplicate scheduler execution may repeat HTTP delivery, so the recipient must use the stable event ID for idempotency. LedgerOps preserves every attempt.

### Timeouts and retry

- connection timeout: 1 second;
- response/total timeout: 5 seconds;
- maximum five delivery attempts;
- delays after failure: 1 second, 5 seconds, 30 seconds, and 120 seconds;
- deterministic ±20% jitter from delivery ID/attempt;
- no retry after a definitive 2xx success;
- 4xx is terminal except `408` and `429`;
- 5xx/timeout/network errors are retryable;
- response bodies are bounded to 64 KiB; only status, latency, safe bounded summary/hash, and headers from an allowlist are retained;
- final failure remains visible and does not affect Payment/financial state.

HTTP occurs outside database transactions. Claim/update uses fenced leases equivalent to the established durable-work model.

### Trigger boundary

Release 0.3 DEV-02 supports manually triggered sandbox test events and predefined scenario events. It does not make NTF-02/NTF-03 general outbound notification preferences a Release 0.3 blocker.

## Consequences

Positive:

- Operator retry cannot create a second retry identity or bypass safety limits.
- Merchant webhook signing remains possible without plaintext secret storage.
- SSRF and redirect risks are explicitly controlled.
- Delivery history/retry state satisfies the developer workflow without affecting financial truth.

Negative or costly:

- Notification needs encryption-key operations and durable HTTP work.
- Public demo configuration must distinguish safe local endpoints from production-like URL policy.
- Webhook recipients still need idempotent handling because HTTP delivery is at least once.

## Alternatives considered

### Manual retry creates a new attempt directly

Rejected because it races with ADR-021 scheduling and can exceed the attempt limit.

### Store only a webhook secret hash

Rejected because a hash cannot sign future outbound requests.

### Store plaintext secret

Rejected because it violates secret-minimization and logging/backup safety.

### Allow arbitrary URLs in a sandbox

Rejected because a public or shared sandbox can still be abused for SSRF.

## Impact assessment

- Product: PRV-04 and DEV-02.
- Modules: Provider owns retry schedule; Payment consumes retry command; Notification owns merchant webhook delivery.
- Data: encrypted endpoint secret/version, delivery work/attempts, lease and audit records.
- Testing: operator/scheduler concurrency, attempt limit, safety denial, encryption round trip/no plaintext, URL/DNS/redirect rejection, HMAC fixtures, timeout/retry/lease recovery, and Tenant/Merchant isolation.

## Review conditions

Reconsider if general external notification delivery becomes a MUST, signed-JWT webhook authentication replaces HMAC, or measured operations require a different retry policy.

## Approval

- Product owner: Approved by delegated instruction on 2026-07-27
- Architecture owner: Approved
- Approved deviations recorded in authoritative documents: Product Definition v1.7 and Technical Specification v1.7
