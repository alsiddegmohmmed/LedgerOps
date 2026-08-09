@org.springframework.modulith.ApplicationModule(
        displayName = "Merchant",
        allowedDependencies = {
                "audit::api",
                "messaging::api",
                "tenancy::api"
        }
)
package com.ledgerops.merchant;
