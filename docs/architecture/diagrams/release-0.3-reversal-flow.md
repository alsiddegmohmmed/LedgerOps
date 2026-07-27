# Release 0.3 Reversal flow

```mermaid
sequenceDiagram
    participant User
    participant Payment
    participant Messaging
    participant Provider
    participant Simulator
    participant Ledger
    participant Audit

    User->>Payment: Request full Reversal + confirmation/reason
    Payment->>Payment: Lock COMPLETED Payment; create REQUESTED Reversal
    Payment->>Audit: Append audit in same transaction
    Payment->>Payment: Start: create Reversal Payment Attempt; PROCESSING
    Payment->>Messaging: SubmitReversalToProvider outbox
    Messaging->>Provider: Kafka at least once
    Provider->>Simulator: Signed HTTP outside DB transaction
    Simulator-->>Provider: Result / later status/webhook
    Provider->>Messaging: ProviderReversalResultObserved
    Messaging->>Payment: Inbox-backed result
    alt definitive SUCCESS
        Payment->>Payment: Lock Payment then Reversal
        Payment->>Ledger: Post exact compensation in joined transaction
        Ledger->>Ledger: Dr MERCHANT_PAYABLE / Cr PROVIDER_CLEARING
        Ledger->>Ledger: source REVERSAL + reversalId; compensates Payment transaction
        Payment->>Payment: Reversal COMPLETED; Payment REVERSED
        Payment->>Audit: Append audit
        Payment->>Messaging: ReversalCompleted outbox
    else definitive failure / safe-to-resubmit
        Payment->>Payment: Reversal FAILED; Payment stays COMPLETED
        User->>Payment: Authorised safe retry
        Payment->>Payment: New immutable attempt; PROCESSING
    else ambiguous/non-final
        Provider->>Provider: Status recovery; no blind resubmission
    end
```
