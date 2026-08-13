@org.springframework.modulith.ApplicationModule(
        displayName = "Reporting",
        allowedDependencies = {
            "audit::api",
            "identity::api",
            "ledger::api",
            "payment::api",
            "provider::api",
            "risk::api",
            "casework::api",
            "reconciliation::api"
        }
)
package com.ledgerops.reporting;
