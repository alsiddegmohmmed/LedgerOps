package com.ledgerops.identity.application;

import com.ledgerops.identity.api.ServiceCredentialMetadata;
import com.ledgerops.identity.api.ServiceCredentialQueryPort;
import com.ledgerops.identity.domain.ServiceCredential;
import com.ledgerops.identity.domain.ServiceCredentialId;
import com.ledgerops.identity.domain.ServiceCredentialRepository;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
class ServiceCredentialQueryService implements ServiceCredentialQueryPort {

    private final ServiceCredentialRepository credentials;

    ServiceCredentialQueryService(ServiceCredentialRepository credentials) {
        this.credentials = Objects.requireNonNull(
                credentials, "Credential repository must not be null");
    }

    @Override
    public Optional<ServiceCredentialMetadata> find(UUID credentialId) {
        Objects.requireNonNull(credentialId, "Credential ID must not be null");
        return credentials.findById(ServiceCredentialId.from(credentialId))
                .map(ServiceCredentialQueryService::metadata);
    }

    private static ServiceCredentialMetadata metadata(ServiceCredential credential) {
        return new ServiceCredentialMetadata(
                credential.id().value(),
                credential.tenantId(),
                credential.merchantId(),
                credential.label(),
                credential.keycloakClientId(),
                credential.status().name(),
                credential.createdBy().value(),
                credential.provisioningOperationId().value(),
                credential.replacesCredentialId() == null
                        ? null : credential.replacesCredentialId().value(),
                credential.disclosureStatus().name(),
                credential.createdAt(),
                credential.updatedAt()
        );
    }
}
