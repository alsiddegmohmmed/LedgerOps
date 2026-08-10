@org.springframework.modulith.ApplicationModule(
        displayName = "Risk",
        allowedDependencies = {"identity::api", "audit::api", "messaging::api"}
)
package com.ledgerops.risk;
