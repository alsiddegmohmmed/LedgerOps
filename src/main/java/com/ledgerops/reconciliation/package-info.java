@org.springframework.modulith.ApplicationModule(
        displayName = "Reconciliation",
        allowedDependencies = {
                "audit::api",
                "identity::api",
                "ledger::api",
                "messaging::api",
                "payment::api",
                "provider::api"
        }
)
package com.ledgerops.reconciliation;
