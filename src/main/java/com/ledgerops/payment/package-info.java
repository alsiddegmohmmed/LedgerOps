@org.springframework.modulith.ApplicationModule(
        displayName = "Payment",
        allowedDependencies = {
            "audit::api",
            "customer::api",
            "ledger::api",
            "merchant::api",
            "messaging::api",
            "provider::api",
            "risk::api",
            "tenancy::api",
            "identity::api"
        }
)
package com.ledgerops.payment;
