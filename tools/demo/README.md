# Local Release 0.3 demo source data

This fixture creates synthetic, authoritative source records for the existing
local demo Tenant and Merchant:

- two Payment records;
- one completed Payment with a balanced Ledger transaction;
- one failed Payment without a Ledger posting.

It does not write to the Reporting projection. Reporting must be rebuilt from
these source records through the application rebuild service so the dashboard,
drill-down, and Payment search remain consistent.

The command is deliberately guarded and idempotent. It does not delete or
reset data.

From the LedgerOps repository:

```bash
LEDGEROPS_DEMO_CONFIRM=YES \
  bash tools/demo/seed-release-0.3-source-data.sh
```

The default target is the local Docker container
`ledgerops-core-postgres-1`. To use a host `psql` connection instead:

```bash
LEDGEROPS_DEMO_CONFIRM=YES \
LEDGEROPS_DEMO_PSQL_MODE=host \
PGHOST=127.0.0.1 \
PGPORT=5432 \
PGPASSWORD=local-ledgerops \
  bash tools/demo/seed-release-0.3-source-data.sh
```

Do not run this against a production database. The Reporting rebuild command
invokes the existing source-boundary rebuild service through the local-only
`demo` Spring profile. It is not a public HTTP endpoint and this fixture never
inserts Reporting facts directly.

After seeding, run the rebuild as a one-shot Core process. Stop the currently
running Core process first so it can use the same database and port:

```bash
LEDGEROPS_DEMO_REBUILD_TENANT_ID=00000000-0000-4000-8000-000000000001 \
LEDGEROPS_DEMO_REBUILD_FROM=2026-08-01T00:00:00Z \
LEDGEROPS_DEMO_REBUILD_TO=2026-08-14T00:00:00Z \
LEDGEROPS_DEMO_REBUILD_AS_OF=2026-08-13T12:00:00Z \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/ledgerops \
SPRING_DATASOURCE_USERNAME=ledgerops \
SPRING_DATASOURCE_PASSWORD=local-ledgerops \
SPRING_PROFILES_ACTIVE=demo \
LEDGEROPS_OBSERVABILITY_KAFKA_LAG_ENABLED=false \
LEDGEROPS_MESSAGING_PUBLISHER_ENABLED=false \
LEDGEROPS_PROVIDER_COMMAND_CONSUMER_ENABLED=false \
LEDGEROPS_PAYMENT_RESULT_CONSUMER_ENABLED=false \
LEDGEROPS_PAYMENT_RETRY_CONSUMER_ENABLED=false \
LEDGEROPS_PROVIDER_EXECUTION_ENABLED=false \
LEDGEROPS_PROVIDER_WEBHOOK_ENABLED=false \
LEDGEROPS_PROVIDER_WEBHOOK_PROCESSING_ENABLED=false \
  ./gradlew :bootRun --no-daemon
```

The leading `:` is important: it scopes Gradle to the root Core application
and prevents the separate Provider Simulator application from starting as
part of the same command. The process exits after the complete generation is switched. The exact
`from`, `to`, and `asOf` values should cover the seeded source timestamps and
the period you want to inspect in Operations Web.
