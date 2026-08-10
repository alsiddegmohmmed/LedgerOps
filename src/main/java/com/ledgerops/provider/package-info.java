@org.springframework.modulith.ApplicationModule(
        displayName = "Provider",
        allowedDependencies = {
                "audit::api",
                "identity::api",
                "messaging::api"
        }
)
package com.ledgerops.provider;
