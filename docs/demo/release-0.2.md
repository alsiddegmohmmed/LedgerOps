# Release 0.2 distributed-processing demo

This demo proves the durable Provider boundary around the completed ADR-020 Payment-to-Ledger transaction. It uses synthetic data and local infrastructure only.

## Prerequisites

- Java 21
- Docker with Compose support
- `curl`

## Verify the release

Run the automated evidence first:

```bash
./gradlew test --console=plain
./gradlew check --console=plain
```

The suites prove atomic submission, outage recovery, duplicate command/result/webhook safety, timeout-to-status-recovery behavior, retry limits, exact SUCCESS posting, rollback, dead-letter identities, lease fencing, tenant isolation, contracts, and module boundaries.

## Inspect operations evidence

Start the local data and observability services:

```bash
docker compose -f compose.release-0.2.yml up -d
```

If host port `5432` is unavailable, select another port for the Core database:

```bash
LEDGEROPS_CORE_DB_PORT=55432 docker compose -f compose.release-0.2.yml up -d
```

Use the selected port in `SPRING_DATASOURCE_URL` when you start Core. The
default Core database port remains `5432`.

Wait until both PostgreSQL containers and Kafka are ready. Start Core from the
repository root:

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/ledgerops \
SPRING_DATASOURCE_USERNAME=ledgerops \
SPRING_DATASOURCE_PASSWORD=local-ledgerops \
PROVIDER_SIMULATOR_CORE_KEY_ID=core-to-simulator-v1 \
PROVIDER_SIMULATOR_CORE_SECRET=local-core-to-simulator-secret \
PROVIDER_SIMULATOR_WEBHOOK_KEY_ID=simulator-to-core-v1 \
PROVIDER_SIMULATOR_WEBHOOK_SECRET=local-simulator-to-core-secret \
./gradlew bootRun
```

Start Provider Simulator on port 8081 in another terminal:

```bash
SIMULATOR_DATABASE_URL=jdbc:postgresql://localhost:5433/provider_simulator \
SIMULATOR_DATABASE_USERNAME=provider_simulator \
SIMULATOR_DATABASE_PASSWORD=local-provider-simulator \
SIMULATOR_CORE_KEY_ID=core-to-simulator-v1 \
SIMULATOR_CORE_SECRET=local-core-to-simulator-secret \
PROVIDER_SIMULATOR_WEBHOOK_KEY_ID=simulator-to-core-v1 \
PROVIDER_SIMULATOR_WEBHOOK_SECRET=local-simulator-to-core-secret \
./gradlew :applications:provider-simulator:bootRun --args='--server.port=8081'
```

These values are disposable local-development secrets. The two applications use
separate PostgreSQL databases. Prometheus uses
`observability/prometheus/prometheus.yml`, and Grafana loads both provisioned
Release 0.2 dashboards from `observability/grafana/dashboards`.

Verify:

- Core metrics: `http://localhost:8080/actuator/prometheus`
- Simulator metrics: `http://localhost:8081/actuator/prometheus`
- Prometheus targets: `http://localhost:9090/targets`
- Grafana dashboards: `LedgerOps Release 0.2 Messaging` and
  `LedgerOps Release 0.2 Provider Operations`

Use deterministic automated scenarios for success, decline, temporary failure, timeout recovery, duplicate webhook, delayed webhook, missing webhook, out-of-order webhook, invalid signature, and conflicting result. Release 0.2 intentionally provides no unauthenticated scenario-administration or replay API.

## Expected final evidence

- `SUCCESS` produces one accepted final result, one `PaymentCompleted` event, and the exact ADR-020 Ledger posting.
- Duplicate delivery creates no additional Payment transition or Ledger entry.
- `UNKNOWN` leaves Payment `PROCESSING` and enters status recovery.
- Exhaustion remains durable and does not invent `FAILED`.
- Dead and conflicting evidence remains queryable and visible through bounded metrics and the operations runbook.

## Reset the local environment

Stop both applications. The following command permanently removes the disposable
local PostgreSQL and Kafka data created by this demo:

```bash
docker compose -f compose.release-0.2.yml down -v
```

Run the startup steps again to create a clean Release 0.2 environment. Do not use
this reset command against a shared or non-disposable environment.
