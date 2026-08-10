@org.springframework.modulith.ApplicationModule(
        displayName = "Merchant",
        allowedDependencies = {
                "audit::api",
                "identity::api",
                "messaging::api",
                "tenancy::api"
        }
)
package com.ledgerops.merchant;
