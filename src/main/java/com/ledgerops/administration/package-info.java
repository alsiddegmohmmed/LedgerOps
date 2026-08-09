@org.springframework.modulith.ApplicationModule(
        displayName = "Administration",
        allowedDependencies = {
                "audit::api",
                "identity::api",
                "merchant::api",
                "tenancy::api"
        }
)
package com.ledgerops.administration;
