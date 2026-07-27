# Release 0.3 messaging topology

```mermaid
flowchart LR
    Payment --> PC[ledgerops.provider.commands.v1]
    PC --> Provider
    Provider --> PR[ledgerops.provider.results.v1]
    PR --> Payment

    Payment --> PL[ledgerops.payment.lifecycle.v1]
    Tenancy --> TL[ledgerops.tenancy.lifecycle.v1]
    Merchant --> TL
    Identity --> IL[ledgerops.identity.lifecycle.v1]
    Provider --> PHL[ledgerops.provider.lifecycle.v1]
    Risk --> RL[ledgerops.risk.lifecycle.v1]

    Payment --> CC[ledgerops.casework.commands.v1]
    Reconciliation --> CC
    CC --> Casework
    Casework --> CL[ledgerops.casework.lifecycle.v1]
    Reconciliation --> RCL[ledgerops.reconciliation.lifecycle.v1]

    PL --> Reporting
    TL --> Reporting
    IL --> Reporting
    PHL --> Reporting
    RL --> Reporting
    CL --> Reporting
    RCL --> Reporting

    TL --> Notification
    IL --> Notification
    PHL --> Notification
    RL --> Notification
    CL --> Notification
    RCL --> Notification
```

All publications use the transactional outbox. All consumers use inbox identity `consumerName + messageId`. ADR-025 defines exact producer/topic/dedup/partition/consumer names; ADR-027 defines Provider scenario/health and operational-projection semantics.
