@org.springframework.modulith.ApplicationModule(
        displayName = "Casework",
        allowedDependencies = {
                "identity::api",
                "payment::api",
                "audit::api",
                "messaging::api"
        }
)
package com.ledgerops.casework;
