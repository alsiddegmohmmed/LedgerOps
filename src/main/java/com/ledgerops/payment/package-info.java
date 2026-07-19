@org.springframework.modulith.ApplicationModule(
        displayName = "Payment",
        allowedDependencies = {
            "customer::api",
            "merchant::api",
            "tenancy::api"
        }
)
package com.ledgerops.payment;
