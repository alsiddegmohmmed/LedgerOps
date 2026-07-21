# Release 0.2 Provider flow

## Module ownership and dependency direction

```mermaid
flowchart LR
    Payment["Payment module"] -->|"messaging::api"| Outbox[("Messaging outbox")]
    Outbox -->|"SubmitPaymentToProvider"| Kafka[("Kafka")]
    Kafka -->|"inbox + durable work"| Provider["Provider module"]
    Provider -->|"signed submission or status query"| Simulator["Provider Simulator"]
    Simulator --> SimulatorDb[("Simulator PostgreSQL")]
    Simulator -->|"signed webhook"| Provider
    Provider --> ProviderDb[("Provider schema")]
    Provider -->|"ProviderResultObserved"| Outbox
    Outbox --> Kafka
    Kafka -->|"inbox + evidence verification"| Payment
    Payment -->|"SUCCESS through ADR-020"| Ledger["ledger::api"]
    Ledger --> CoreDb[("Core PostgreSQL")]
    Provider -->|"PaymentSubmissionRetryRequested"| Outbox
```

Payment depends on `provider::api` and `messaging::api`; Provider depends only on
`messaging::api`. Provider never queries Payment data, and Payment never queries
Provider tables. The Provider Simulator is separately deployable and cannot access
the Core database.

Provider network calls occur outside database transactions. Business changes and
outbox intent commit in short PostgreSQL transactions. Kafka delivery is at least
once, so duplicate publication is expected. Inbox identity, business outbox
identity, Provider evidence identity, accepted-final-result evidence, and Ledger
source uniqueness reduce duplicates to one accepted business and financial effect.

`UNKNOWN`, `ACCEPTED`, and `PENDING` schedule status recovery without changing the
Payment from `PROCESSING`. A safe intentional retry creates a new immutable Payment
Attempt through `PaymentSubmissionRetryRequested`; it never repeats the original
submission work. Only definitive `SUCCESS` enters the unchanged ADR-020 completion
transaction.

## Initial submission and Provider result

```mermaid
sequenceDiagram
    participant P as Payment
    participant M as Messaging
    participant K as Kafka
    participant R as Provider
    participant S as Provider Simulator
    participant L as Ledger
    P->>P: Lock APPROVED Payment
    P->>P: Create immutable attempt and set PROCESSING
    P->>M: Append SubmitPaymentToProvider
    Note over P,M: One PostgreSQL transaction
    M-->>K: Publish after commit
    K->>R: Deliver command at least once
    R->>R: Commit inbox and durable SUBMISSION work
    R->>S: Signed request outside DB transaction
    S->>S: Commit Provider idempotency record
    R->>R: Commit immutable evidence and ProviderResultObserved
    R->>M: Append result outbox record
    M-->>K: Publish after commit
    K->>P: Deliver result at least once
    P->>R: Verify authoritative evidence through provider::api
    alt SUCCESS
        P->>L: Invoke unchanged ADR-020 completion
        Note over P,L: One joined PostgreSQL transaction
    else definitive failure
        P->>P: Set FAILED and append PaymentFailed
    else non-final result
        P->>P: Keep PROCESSING
    end
```

The Payment submission transaction commits the lifecycle transition, immutable
attempt, and command intent together. The Provider call happens only after the
consumer has committed durable work. Stage A commits Provider evidence before
publication; Stage B verifies that evidence before applying any Payment result.

## Outbox and inbox crash windows

```mermaid
flowchart TD
    Claim["Claim due outbox row with a new lease token"] --> Publish["Publish to Kafka outside the claim transaction"]
    Publish --> Ack{"Kafka acknowledged?"}
    Ack -->|"No"| Retry["Current token records RETRYABLE or DEAD"]
    Ack -->|"Yes"| Mark["Current token records PUBLISHED"]
    Publish -. "process crash" .-> Reclaim["Expired lease is reclaimed with a new token"]
    Reclaim --> Publish
    Publish --> Consumer["Consumer inserts inbox and business effect together"]
    Consumer --> Duplicate{"consumerName + messageId already exists?"}
    Duplicate -->|"Yes"| NoEffect["Acknowledge without another effect"]
    Duplicate -->|"No"| Commit["Commit PROCESSED and business effect"]
```

Kafka acknowledgement followed by a publisher crash can publish the same message
again. Fenced leases prevent stale database updates; inbox identity prevents the
duplicate delivery from repeating the business effect. The system does not claim
end-to-end exactly-once delivery.

## Webhook reception and asynchronous processing

```mermaid
sequenceDiagram
    participant S as Provider Simulator
    participant W as Provider webhook API
    participant R as Provider schema
    participant M as Messaging
    S->>W: Signed webhook plus unsigned trace context
    W->>W: Enforce 256 KiB limit, timestamp, key direction, and HMAC
    alt Authentication fails
        W->>R: Bounded platform rejection only
        W-->>S: 401 or 413
    else Authenticated but malformed
        W->>R: Provider-scoped unattributed invalid evidence
        W-->>S: 400
    else No Provider-owned mapping
        W->>R: Durable unattributed receipt and signal
        W-->>S: 202
    else Mapped event
        W->>R: Tenant-owned receipt, canonical event, and work
        W-->>S: 202 or duplicate/conflict result
        R->>R: Claim work with fenced lease
        R->>R: Commit immutable interaction/result evidence
        R->>M: Append ProviderResultObserved
    end
```

Webhook payload fields never establish tenant identity. Provider resolves identity
from the mapping persisted when it consumed `SubmitPaymentToProvider`. Duplicate and
conflicting receipts remain evidence and never overwrite an accepted final result.

## Local deployment

```mermaid
flowchart LR
    Client["Local client"] --> Core["Core Spring Boot app :8080"]
    Core --> CoreDb[("Core PostgreSQL :5432")]
    Core <--> Kafka[("Kafka :9092")]
    Core --> Simulator["Provider Simulator :8081"]
    Simulator --> SimulatorDb[("Simulator PostgreSQL :5433")]
    Simulator --> Core
    Prometheus["Prometheus :9090"] --> Core
    Prometheus --> Simulator
    Grafana["Grafana :3000"] --> Prometheus
```

Core and Provider Simulator run as separate applications and use different database
credentials. The Simulator has no route or credentials for the Core database.
Prometheus scrapes bounded application metrics, and Grafana loads separate messaging
and Provider-operations dashboards.
