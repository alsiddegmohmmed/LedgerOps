# Release 0.3 system context

```mermaid
flowchart LR
    Browser[Operations Web browser] --> BFF[Next.js BFF]
    BFF <--> Keycloak
    BFF --> Core[LedgerOps Core modular monolith]
    MerchantSystem[Merchant integration] --> Keycloak
    MerchantSystem --> Core
    Core <--> PostgreSQL[(Core PostgreSQL)]
    Core <--> Kafka[(Kafka)]
    BFF <--> Redis[(Redis session state)]
    Core <--> MinIO[(MinIO/S3 raw settlement files)]
    Core <--> Simulator[Provider Simulator]
    Simulator <--> SimulatorDB[(Simulator PostgreSQL)]
    Simulator --> MinIO
    Core --> Telemetry[OpenTelemetry / Prometheus / Grafana]
```

Boundary rules:

- Keycloak authenticates; Core PostgreSQL authorizes.
- Redis stores ephemeral BFF sessions only.
- Provider Simulator never accesses Core PostgreSQL.
- Frontend never accesses databases or Kafka.
- PostgreSQL remains transactional/authorization truth; object storage holds immutable raw evidence.

Provider scenarios, product health, operational projections, notifications, and SSE follow ADR-027.
