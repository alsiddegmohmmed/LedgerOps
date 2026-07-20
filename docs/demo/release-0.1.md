# Release 0.1 local demonstration

This guide starts the Transactional Core locally, loads synthetic data, and demonstrates Payment creation, equivalent replay, conflicting idempotency reuse, and coordinated duplicate requests.

Release 0.1 deliberately has no authentication. Run it only on a trusted local machine. Identity and authorization begin in Release 0.3.

## Prerequisites

- Java 21
- Docker
- `curl`

## 1. Start PostgreSQL

```bash
docker run --name ledgerops-postgres \
  -e POSTGRES_DB=ledgerops \
  -e POSTGRES_USER=ledgerops \
  -e POSTGRES_PASSWORD=ledgerops-local-only \
  -p 127.0.0.1:5432:5432 \
  -d postgres:17-alpine
```

The password above is a disposable local-development value, not a deployable secret.

## 2. Start LedgerOps

In the repository root:

```bash
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/ledgerops
export SPRING_DATASOURCE_USERNAME=ledgerops
export SPRING_DATASOURCE_PASSWORD=ledgerops-local-only
./gradlew bootRun
```

Flyway installs migrations V1 through V7. Wait until the application reports that it started.

Verify health from another terminal:

```bash
curl --fail --silent http://localhost:8080/actuator/health
```

Expected result:

```json
{"status":"UP"}
```

## 3. Load synthetic demonstration data

```bash
docker exec -i ledgerops-postgres \
  psql -U ledgerops -d ledgerops \
  < docs/demo/release-0.1-seed.sql
```

The seed is repeatable and contains only fixed synthetic identifiers. It creates one active tenant, merchant, customer, versioned Risk profile, threshold rule, and the six approved SAR Ledger accounts.

## 4. Create a Payment

```bash
curl --include \
  --request POST http://localhost:8080/api/v1/payments \
  --header 'Content-Type: application/json' \
  --data '{
    "tenantId": "10000000-0000-0000-0000-000000000001",
    "merchantId": "20000000-0000-0000-0000-000000000001",
    "customerId": "30000000-0000-0000-0000-000000000001",
    "amount": 125.00,
    "currency": "SAR",
    "paymentMethodCategory": "card",
    "idempotencyKey": "demo-payment-001"
  }'
```

Expected result: `201 Created`, an `X-Correlation-Id` header, and a Payment in `CREATED` state.

Run the same command again. Expected result: `200 OK` with the same Payment identity and no duplicate row.

## 5. Demonstrate conflicting reuse

Repeat the request with the same `tenantId` and `idempotencyKey`, but change `amount` to `126.00`.

Expected result: `409 Conflict` with `code` equal to `PAYMENT_IDEMPOTENCY_CONFLICT`. The RFC 7807 response states that the existing Payment was unchanged, automatic retry is disabled, and the caller must reuse the original content or choose a new key.

## 6. Demonstrate coordinated duplicates

Use a fresh key and issue eight requests concurrently:

```bash
seq 1 8 | xargs -I{} -P8 curl --silent \
  --request POST http://localhost:8080/api/v1/payments \
  --header 'Content-Type: application/json' \
  --data '{
    "tenantId": "10000000-0000-0000-0000-000000000001",
    "merchantId": "20000000-0000-0000-0000-000000000001",
    "customerId": "30000000-0000-0000-0000-000000000001",
    "amount": 50.00,
    "currency": "SAR",
    "paymentMethodCategory": "card",
    "idempotencyKey": "demo-concurrent-001"
  }'
```

Every response returns the same logical Payment. Confirm the final database state:

```bash
docker exec ledgerops-postgres \
  psql -U ledgerops -d ledgerops -Atc \
  "SELECT count(*) FROM payment.payments WHERE tenant_id = '10000000-0000-0000-0000-000000000001' AND idempotency_key = 'demo-concurrent-001';"
```

Expected result: `1`.

## Scope and evidence

- The complete API contract is [ledgerops-openapi-v0.1.yaml](../api/ledgerops-openapi-v0.1.yaml).
- Risk evaluation and Payment-success completion are internal Release 0.1 boundaries, not arbitrary public endpoints.
- The automated suite demonstrates deterministic Risk decisions and atomic Payment-to-Ledger completion, including forced rollback and concurrency.
- Provider attempts, callbacks, result deduplication, inbox, outbox, and Kafka begin in Release 0.2.

## Reset

Stop LedgerOps, then remove the disposable database container:

```bash
docker rm --force ledgerops-postgres
```

Repeat the guide from step 1 for a clean environment.
