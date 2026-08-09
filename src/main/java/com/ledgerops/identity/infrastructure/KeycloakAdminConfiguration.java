package com.ledgerops.identity.infrastructure;

import com.ledgerops.identity.api.ServiceCredentialProvisioningPort;
import com.ledgerops.identity.api.ServiceCredentialRevocationPort;
import com.ledgerops.identity.application.KeycloakCredentialDisabler;
import com.ledgerops.identity.application.KeycloakCredentialProvisioner;
import com.ledgerops.identity.application.ServiceCredentialProvisioningService;
import com.ledgerops.identity.application.ServiceCredentialRevocationService;
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
    KeycloakCredentialProvisioningAdapter keycloakCredentialAdminAdapter(
            KeycloakAdminProperties properties
    ) {
        return new KeycloakCredentialProvisioningAdapter(properties);
    }

    @Bean
    @ConditionalOnProperty(
            name = "ledgerops.identity.keycloak.admin.enabled",
            havingValue = "true"
    )
    KeycloakCredentialProvisioner keycloakCredentialProvisioner(
            KeycloakCredentialProvisioningAdapter adapter
    ) {
        return adapter;
    }

    @Bean
    @ConditionalOnProperty(
            name = "ledgerops.identity.keycloak.admin.enabled",
            havingValue = "true"
    )
    KeycloakCredentialDisabler keycloakCredentialDisabler(
            KeycloakCredentialProvisioningAdapter adapter
    ) {
        return adapter;
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
            KeycloakCredentialDisabler disabler,
            TransactionTemplate transactions,
            Clock clock
    ) {
        return new ServiceCredentialProvisioningService(
                credentials,
                operations,
                keycloak,
                disabler,
                transactions,
                clock
        );
    }

    @Bean
    @ConditionalOnProperty(
            name = "ledgerops.identity.keycloak.admin.enabled",
            havingValue = "true"
    )
    ServiceCredentialRevocationPort serviceCredentialRevocationPort(
            ServiceCredentialRepository credentials,
            CredentialProvisioningOperationRepository operations,
            KeycloakCredentialDisabler keycloak,
            TransactionTemplate transactions,
            Clock clock
    ) {
        return new ServiceCredentialRevocationService(
                credentials,
                operations,
                keycloak,
                transactions,
                clock
        );
    }
}
