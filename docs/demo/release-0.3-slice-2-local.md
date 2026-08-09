# Release 0.3 Slice 2 local Operations Web

This local workflow starts the approved dependencies required by the Operations Web BFF authentication shell. Redis stores ephemeral OAuth transactions and sessions. Keycloak authenticates. Core PostgreSQL remains authorization truth.

## Prerequisites

- Java 21
- Docker with Compose support
- Node.js 24.18.0 within the supported 24.x line
- pnpm 11.4.0

From the repository root, select the pinned Node version with `nvm use` or the equivalent command for your Node version manager.

## Start local infrastructure

The Release 0.3 Compose file adds Redis and Keycloak to the completed Release 0.2 PostgreSQL and Kafka infrastructure:

```bash
docker compose \
  -f compose.release-0.2.yml \
  -f compose.release-0.3.yml \
  up -d --wait core-postgres kafka redis keycloak
```

If host port `5432` is already occupied, use the documented Core database override:

```bash
LEDGEROPS_CORE_DB_PORT=55432 docker compose \
  -f compose.release-0.2.yml \
  -f compose.release-0.3.yml \
  up -d --wait core-postgres kafka redis keycloak
```

Verify Redis and Keycloak before starting the applications:

```bash
docker compose \
  -f compose.release-0.2.yml \
  -f compose.release-0.3.yml \
  exec redis redis-cli ping

curl --fail \
  http://localhost:8180/realms/ledgerops/.well-known/openid-configuration
```

Redis must return `PONG`, and the Keycloak request must return the realm metadata.

## Start Core

In a separate terminal, start Core from the repository root. If you selected port `55432`, use it in `SPRING_DATASOURCE_URL` instead of `5432`.

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/ledgerops \
SPRING_DATASOURCE_USERNAME=ledgerops \
SPRING_DATASOURCE_PASSWORD=local-ledgerops \
KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
LEDGEROPS_OBSERVABILITY_KAFKA_LAG_ENABLED=false \
LEDGEROPS_MESSAGING_PUBLISHER_ENABLED=false \
LEDGEROPS_PROVIDER_COMMAND_CONSUMER_ENABLED=false \
LEDGEROPS_PAYMENT_RESULT_CONSUMER_ENABLED=false \
LEDGEROPS_PAYMENT_RETRY_CONSUMER_ENABLED=false \
LEDGEROPS_PROVIDER_EXECUTION_ENABLED=false \
LEDGEROPS_PROVIDER_WEBHOOK_ENABLED=false \
LEDGEROPS_PROVIDER_WEBHOOK_PROCESSING_ENABLED=false \
LEDGEROPS_IDENTITY_JWT_ISSUER=http://localhost:8180/realms/ledgerops \
LEDGEROPS_IDENTITY_JWT_AUDIENCE=operations-web \
LEDGEROPS_IDENTITY_JWT_JWK_SET_URI=http://localhost:8180/realms/ledgerops/protocol/openid-connect/certs \
LEDGEROPS_IDENTITY_PLATFORM_ADMIN_BOOTSTRAP_ENABLED=true \
LEDGEROPS_IDENTITY_PLATFORM_ADMIN_ISSUER=http://localhost:8180/realms/ledgerops \
LEDGEROPS_IDENTITY_PLATFORM_ADMIN_SUBJECT=11111111-1111-4111-8111-111111111111 \
PROVIDER_SIMULATOR_CORE_KEY_ID=local-disabled \
PROVIDER_SIMULATOR_CORE_SECRET=local-disabled \
PROVIDER_SIMULATOR_WEBHOOK_KEY_ID=local-disabled \
PROVIDER_SIMULATOR_WEBHOOK_SECRET=local-disabled \
./gradlew bootRun
```

Wait for `http://localhost:8080/actuator/health` to report healthy.

## Start Operations Web

In another terminal:

```bash
cd applications/operations-web
pnpm dev
```

Open `http://localhost:3001` and sign in with the disposable local realm user:

```text
username: slice1-human
password: slice1-password
```

The local realm proves browser authentication. It does not seed Tenant business data or grant Tenant authority. The authenticated Playwright suites create isolated PostgreSQL evidence for complete Tenant-scoped UI workflows.

## Stop local infrastructure

Stop Core and Operations Web with `Ctrl+C`, then stop the containers:

```bash
docker compose \
  -f compose.release-0.2.yml \
  -f compose.release-0.3.yml \
  down
```

Do not add `-v` unless you intentionally want to delete the disposable local database state.
