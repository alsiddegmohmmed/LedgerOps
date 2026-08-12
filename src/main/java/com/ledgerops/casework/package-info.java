@org.springframework.modulith.ApplicationModule(
        displayName = "Casework",
        allowedDependencies = {
                "identity::api",
                "payment::api",
                "audit::api",
                "messaging::api",
                "reconciliation::api",
                "ledger::api"
        }
)
package com.ledgerops.casework;
