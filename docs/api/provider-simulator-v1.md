# Provider Simulator HTTP contract v1

Status: Implemented for Release 0.2 Slice 3

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

## Verification

Run:

```bash
./gradlew :applications:provider-simulator:test --console=plain
./gradlew :test --tests '*Provider*' --console=plain
```

Both suites use PostgreSQL Testcontainers. The Core tests also prove that Provider HTTP execution is rejected while a database transaction is active.
