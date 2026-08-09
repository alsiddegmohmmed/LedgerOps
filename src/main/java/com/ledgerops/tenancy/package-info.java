@org.springframework.modulith.ApplicationModule(
        displayName = "Tenancy",
        allowedDependencies = {
                "audit::api",
                "identity::api",
                "messaging::api"
        }
)
package com.ledgerops.tenancy;
