# Release 0.3 module dependency graph

```mermaid
flowchart TD
    Administration --> Identity
    Administration --> Tenancy
    Administration --> Merchant
    Administration --> Risk
    Administration --> Provider
    Administration --> Audit
    Administration --> Messaging

    Identity --> Audit
    Identity --> Messaging
    Ledger --> Identity
    Tenancy --> Identity
    Tenancy --> Audit
    Tenancy --> Messaging
    Merchant --> Tenancy
    Merchant --> Identity
    Merchant --> Audit
    Merchant --> Messaging
    Customer --> Tenancy
    Customer --> Merchant
    Risk --> Identity
    Risk --> Audit
    Risk --> Messaging
    Provider --> Identity
    Provider --> Audit
    Provider --> Messaging
    Payment --> Tenancy
    Payment --> Merchant
    Payment --> Customer
    Payment --> Identity
    Payment --> Risk
    Payment --> Provider
    Payment --> Ledger
    Payment --> Audit
    Payment --> Messaging
    Reconciliation --> Identity
    Reconciliation --> Payment
    Reconciliation --> Provider
    Reconciliation --> Ledger
    Reconciliation --> Audit
    Reconciliation --> Messaging
    Casework --> Identity
    Casework --> Payment
    Casework --> Reconciliation
    Casework --> Ledger
    Casework --> Audit
    Casework --> Messaging
    Notification --> Identity
    Notification --> Messaging
    Reporting --> Identity
    Reporting --> Payment
    Reporting --> Risk
    Reporting --> Provider
    Reporting --> Ledger
    Reporting --> Reconciliation
    Reporting --> Casework
    Reporting --> Audit
    Reporting --> Messaging
```

Identity has no business-module dependency. Audit and Messaging have none. Administration owns cross-module Platform/Tenant administration and no duplicate business truth. Payment does not depend on Casework/Reconciliation; Reconciliation does not depend on Casework. Reporting uses read-only published APIs/events and never source tables or mutation APIs.
