# Release 0.3 Slice 5 Provider and Merchant webhook contracts

Status: Implemented for Slice 5 scope

This reference describes the authenticated Slice 5 HTTP boundaries and the
versioned integration fixtures. Product behavior and security rules remain
owned by the Release 0.3 plan and ADR-026/ADR-027.

## Risk configuration

Tenant-scoped routes:

```text
GET /api/v1/tenants/{tenantId}/risk/configuration
GET /api/v1/tenants/{tenantId}/risk/configuration/history
PUT /api/v1/tenants/{tenantId}/risk/configuration
```

Reads require Risk read authority. `PUT` requires Tenant-wide
`risk:configuration-manage`, `confirmation: true`, a non-blank reason, and
the current `expectedVersion`. A successful update appends a new version and
preserves the previous version and evaluation references. Merchant-scoped
authority cannot update this resource. A stale version returns a conflict.

## Provider scenarios

Platform Admin routes:

```text
POST /api/v1/platform/provider/scenarios/profiles
POST /api/v1/platform/provider/scenarios/assignments
GET  /api/v1/platform/provider/scenarios/assignments
GET  /api/v1/platform/provider/scenarios/profiles/{profileId}/{version}
GET  /api/v1/platform/provider/scenarios/pins?tenantId={tenantId}&paymentId={paymentId}&operationType=PAYMENT
```

Profiles are immutable versioned records. A delayed webhook requires a
positive delay. Assignments use `GLOBAL`, `TENANT`, or `PAYMENT` scope and
resolve in the order `PAYMENT > TENANT > GLOBAL > default SUCCESS`.

The first Payment attempt stores the selected profile ID, profile version,
and canonical snapshot. Retry and status-query work reuse that snapshot.
Changing an assignment does not rewrite an existing pin.

The Simulator v2 request schema is
[`SubmitPaymentToProvider.schema.json`](../../packages/provider-contracts/v2/SubmitPaymentToProvider.schema.json).
The valid cross-application fixture is
[`submit-payment-scenario-valid.json`](../../packages/provider-contracts/v2/fixtures/submit-payment-scenario-valid.json).
The existing v1 Simulator request remains supported.

## Provider health

Tenant-scoped routes:

```text
GET /api/v1/tenants/{tenantId}/provider/health
GET /api/v1/tenants/{tenantId}/provider/health/history
```

Platform Admin routes use the corresponding `/api/v1/platform/provider/health`
and `/history` paths. Health evaluations are append-only and the current
pointer is updated separately. The states are exactly `UNKNOWN`, `HEALTHY`,
`DEGRADED`, and `UNAVAILABLE`. The seeded Simulator policy is a five-minute
window, 30-second evaluation interval, and minimum of 10 completed calls.

## Payment retry now

```text
POST /api/v1/tenants/{tenantId}/payments/{paymentId}/retry
```

The request contains `confirmation: true` and a non-blank `reason`. Core
requires the Payment to be `PROCESSING`, below the three-attempt limit, and
within the caller's effective Merchant scope. The action only advances an
existing fenced `WAITING_RETRY_REQUEST` whose Provider evidence is
`SAFE_TO_RESUBMIT`; it does not create an attempt, retry request, outbox
message, or financial effect. The Provider worker and Payment consumer retain
those responsibilities.

## Merchant webhooks

Tenant/Merchant routes:

```text
GET    /api/v1/tenants/{tenantId}/merchants/{merchantId}/webhooks
POST   /api/v1/tenants/{tenantId}/merchants/{merchantId}/webhooks
POST   /api/v1/tenants/{tenantId}/merchants/{merchantId}/webhooks/{endpointId}/rotate
DELETE /api/v1/tenants/{tenantId}/merchants/{merchantId}/webhooks/{endpointId}
POST   /api/v1/tenants/{tenantId}/merchants/{merchantId}/webhooks/{endpointId}/test-events
GET    /api/v1/tenants/{tenantId}/merchants/{merchantId}/webhooks/{endpointId}/deliveries
```

The create request supplies a label, an HTTPS endpoint URL, and one or more
allowed event types. Local HTTP is available only when the explicit local
development property is enabled. URLs with credentials, query strings,
fragments, prohibited IPv4/IPv6 destinations, or redirects are rejected.

The plaintext HMAC secret is returned only by create and rotate. Core stores
only encrypted secret material. Revocation cancels unclaimed or retryable
delivery work, and the worker rechecks endpoint status before sending.

The delivery worker uses a one-second connection timeout, a five-second total
request timeout, no redirects, at most five attempts, and the ADR-026 retry
schedule. The signed canonical input is:

```text
v1
POST
<RAW_PATH_WITHOUT_QUERY>
<ENDPOINT_ID>
<KEY_VERSION>
<TIMESTAMP>
<EVENT_ID>
<LOWERCASE_SHA256_RAW_BODY>
```

The signature header is `X-LedgerOps-Webhook-Signature` with the value
`v1=<unpadded base64url HMAC-SHA256>`. Delivery responses retain only bounded
safe summaries and response hashes.

The canonical fixture is
[`merchant-webhook-canonical.json`](../../packages/notification-contracts/v1/fixtures/merchant-webhook-canonical.json).

## Slice boundary

Slice 5 does not implement general notification preferences, the Notification
consumer/read-state workflow, Reversal retry, or production business-event
producers beyond synthetic webhook test events. Those items remain owned by
their later slices in the approved Release 0.3 plan.
