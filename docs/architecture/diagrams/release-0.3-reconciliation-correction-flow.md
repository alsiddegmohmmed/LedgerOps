# Release 0.3 Reconciliation, settlement posting, and correction flow

```mermaid
sequenceDiagram
    participant Analyst
    participant Object as MinIO/S3
    participant Reconciliation
    participant PaymentAPI as payment::api
    participant ProviderAPI as provider::api
    participant Ledger
    participant Messaging
    participant Casework

    Analyst->>Object: Stream immutable settlement file; compute SHA-256
    Analyst->>Reconciliation: Insert/find batch metadata/version
    Reconciliation->>Reconciliation: Spring Batch normalize immutable record versions
    Reconciliation->>PaymentAPI: Page immutable Payment/Reversal completion facts
    Reconciliation->>ProviderAPI: Page immutable Provider references
    Reconciliation->>Reconciliation: Create immutable snapshot/run/results
    Reconciliation->>Reconciliation: Lock BatchFamilyControl + current pointer
    alt candidate invalidates uncompensated posting
        Reconciliation->>Messaging: CreateCaseRequested(discrepancy)
        Messaging->>Casework: Inbox-backed one Case
    else eligible current run
        Reconciliation->>Reconciliation: Promote pointer
        Reconciliation->>Reconciliation: Create/find SettlementPostingInstruction
        Reconciliation->>Ledger: Exact settlement posting/replay
    end

    alt approved invalidated settlement posting
        Casework->>Reconciliation: Lock/verify family, pointer, instruction
        Casework->>Ledger: Exact inverse AUTHORISED_CORRECTION
        Casework->>Casework: Complete correction and Case evidence atomically
        Reconciliation->>Reconciliation: Later promote corrected run
        Reconciliation->>Ledger: New instruction/new exact posting
    end
```

Every promotion, settlement posting, and correction locks the same Reconciliation `BatchFamilyControl` first. Reconciliation never writes Payment, Provider, Ledger, or Casework tables directly.
