@org.springframework.modulith.ApplicationModule(
        displayName = "Identity",
        allowedDependencies = {
            "audit::api",
            "messaging::api"
        }
)
package com.ledgerops.identity;
