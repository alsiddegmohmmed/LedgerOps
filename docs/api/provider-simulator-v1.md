# Provider Simulator HTTP contract v1

Status: Implemented for Release 0.2

The Provider Simulator is a separate Spring Boot application in `applications/provider-simulator`. It owns a separate PostgreSQL database and has no access to the LedgerOps Core database.

## Authentication

Core signs each request with a Core-to-Simulator key. Do not reuse the later Simulator-to-Core webhook key set.

Required headers:

- `Content-Type: application/json`
- `X-LedgerOps-Key-Id`
- `X-LedgerOps-Timestamp`: decimal Unix epoch seconds
- `X-LedgerOps-Request-Id`: canonical UUID
- `X-LedgerOps-Signature`: `v1=` followed by an unpadded base64url HMAC-SHA256 value

Canonical UTF-8 input:

```text
v1
<UPPERCASE_HTTP_METHOD>
<RAW_PATH_WITHOUT_QUERY>
<KEY_ID>
<TIMESTAMP_DECIMAL>
<REQUEST_ID>
<LOWERCASE_SHA256_RAW_BODY>
```

Use ASCII LF separators and no trailing LF. Trace headers propagate separately and are not signed. The Simulator accepts timestamps within 300 seconds of its injected clock. An unknown key, wrong-direction key, invalid timestamp, or invalid signature returns RFC 7807 `401 Unauthorized`.

When present, `traceparent` must use canonical W3C version `00` form. `tracestate`
is bounded to 512 characters. Core persists trace context with delayed Provider work,
and the Simulator returns it with the corresponding webhook. Neither header is part
of the HMAC canonical input.

The cross-application golden fixture is `packages/provider-contracts/v1/fixtures/hmac-core-to-simulator.json`.

## Submit a Payment

`POST /provider/v1/payments`

The body is the version-1 `SubmitPaymentToProvider` payload. Provider-side idempotency is exactly `providerClientId + providerIdempotencyKey`. The Simulator commits the idempotency record and Provider transaction when it accepts the request. Equivalent reuse returns the original Provider transaction; changed content returns RFC 7807 `409 Conflict` and does not replace the transaction.

## Query Payment status

`POST /provider/v1/payment-status-queries`

```json
{
  "providerIdempotencyKey": "payment:<paymentId>"
}
```

An existing Provider transaction returns its stable `providerResultId`, Provider reference, and result category. A missing transaction returns `404`. Finding an existing transaction prohibits resubmission.

## Timeouts and outcomes

Core uses a 1-second connection timeout, 3-second read timeout, and 5-second total timeout. Any potentially transmitted timeout becomes `UNKNOWN` with `STATUS_RECOVERY_REQUIRED`; it never triggers blind resubmission. `TEMPORARY_FAILURE` is `SAFE_TO_RESUBMIT` only when the response explicitly proves that no Provider transaction was accepted or created.

The Simulator supports test-, seed-, or local-profile scenario overrides. It exposes no unauthenticated scenario-administration endpoint.

## Deliver a Provider webhook

`POST /internal/provider/v1/webhooks`

The Simulator sends version-1 `ProviderWebhook` payloads to Core. The JSON Schema is `packages/provider-contracts/v1/ProviderWebhook.schema.json`.

Simulator-to-Core webhooks use a separate secret set and `X-LedgerOps-Event-Id` in place of `X-LedgerOps-Request-Id`. The canonical UTF-8 input is:

```text
v1
POST
/internal/provider/v1/webhooks
<KEY_ID>
<TIMESTAMP_DECIMAL>
<EVENT_ID>
<LOWERCASE_SHA256_RAW_BODY>
```

Use ASCII LF separators and no trailing LF. Core reads at most 256 KiB plus one detection byte before authentication or JSON parsing. Core accepts timestamps whose absolute difference from its injected `Clock` is at most 300 seconds.

Reception outcomes are:

| Condition | Result | Durable evidence |
|---|---|---|
| Unknown key, wrong-direction key, invalid signature, or invalid timestamp | `401` | Bounded platform security rejection only; no Provider receipt or work |
| Valid signature with malformed JSON | `400` | Provider-scoped unattributed invalid evidence; no tenant-owned receipt or work |
| Valid payload without a Provider-owned mapping | `202` | Durable unattributed Provider receipt and operational signal; no tenant-owned work |
| New mapped event | `202` | One tenant-owned canonical event, receipt, and asynchronous work item |
| Repeated mapped event with the same identity and payload hash | `202` | Duplicate receipt evidence; no new work |
| Repeated mapped event with changed content | `409` | Conflict receipt and operational evidence; no new work |
| Body larger than 256 KiB | `413` | Bounded platform rejection metadata when available |

Webhook body fields never establish tenant identity. Core resolves tenant, Payment, and Payment Attempt from the Provider-owned mapping created from `SubmitPaymentToProvider`.

The Simulator persists outbound webhook delivery before sending it. Its deterministic test scenarios cover duplicate, delayed, missing, out-of-order, invalid-signature, and conflicting-result delivery. These Provider-to-Core webhooks do not implement DEV-02 merchant webhook testing.

## Verification

Run:

```bash
./gradlew :applications:provider-simulator:test --console=plain
./gradlew :test --tests '*Provider*' --console=plain
./gradlew :test --tests '*ProviderWebhook*' --console=plain
```

Both suites use PostgreSQL Testcontainers. The Core tests also prove that Provider HTTP execution is rejected while a database transaction is active.

Core and Provider Simulator expose bounded Prometheus metrics at
`/actuator/prometheus`. The Release 0.2 dashboards and alerts are under
`observability/`; record-level identifiers remain in traces and structured logs,
not metric labels.
