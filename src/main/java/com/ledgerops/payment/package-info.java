@org.springframework.modulith.ApplicationModule(
        displayName = "Payment",
        allowedDependencies = {
            "customer::api",
            "ledger::api",
            "merchant::api",
            "messaging::api",
            "risk::api",
            "tenancy::api"
        }
)
package com.ledgerops.payment;
