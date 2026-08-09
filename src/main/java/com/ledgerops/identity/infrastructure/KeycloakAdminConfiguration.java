package com.ledgerops.identity.infrastructure;

import com.ledgerops.identity.api.ServiceCredentialProvisioningPort;
import com.ledgerops.identity.application.KeycloakCredentialProvisioner;
import com.ledgerops.identity.application.ServiceCredentialProvisioningService;
import com.ledgerops.identity.domain.CredentialProvisioningOperationRepository;
import com.ledgerops.identity.domain.ServiceCredentialRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(KeycloakAdminProperties.class)
class KeycloakAdminConfiguration {

    @Bean
    @ConditionalOnProperty(
            name = "ledgerops.identity.keycloak.admin.enabled",
            havingValue = "true"
    )
    KeycloakCredentialProvisioner keycloakCredentialProvisioner(KeycloakAdminProperties properties) {
        return new KeycloakCredentialProvisioningAdapter(properties);
    }

    @Bean
    @ConditionalOnProperty(
            name = "ledgerops.identity.keycloak.admin.enabled",
            havingValue = "true"
    )
    ServiceCredentialProvisioningPort serviceCredentialProvisioningPort(
            ServiceCredentialRepository credentials,
            CredentialProvisioningOperationRepository operations,
            KeycloakCredentialProvisioner keycloak,
            TransactionTemplate transactions,
            Clock clock
    ) {
        return new ServiceCredentialProvisioningService(
                credentials,
                operations,
                keycloak,
                transactions,
                clock
        );
    }
}
