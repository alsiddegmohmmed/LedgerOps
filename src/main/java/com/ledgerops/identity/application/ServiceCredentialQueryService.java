package com.ledgerops.identity.application;

import com.ledgerops.identity.api.ServiceCredentialMetadata;
import com.ledgerops.identity.api.ServiceCredentialPage;
import com.ledgerops.identity.api.ServiceCredentialPageQuery;
import com.ledgerops.identity.api.ServiceCredentialQueryPort;
import com.ledgerops.identity.domain.ServiceCredential;
import com.ledgerops.identity.domain.ServiceCredentialId;
import com.ledgerops.identity.domain.ServiceCredentialRepository;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.List;
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

    @Override
    public ServiceCredentialPage findPage(ServiceCredentialPageQuery query) {
        Objects.requireNonNull(query, "Credential page query must not be null");
        List<ServiceCredential> fetched = credentials.findPage(
                query.tenantId(),
                query.merchantId(),
                query.status() == null
                        ? null
                        : com.ledgerops.identity.domain.ServiceCredentialStatus.valueOf(query.status()),
                query.beforeCreatedAt(),
                query.beforeCredentialId() == null
                        ? null
                        : ServiceCredentialId.from(query.beforeCredentialId()),
                query.limit() + 1
        );
        boolean hasNext = fetched.size() > query.limit();
        List<ServiceCredentialMetadata> page = hasNext
                ? fetched.subList(0, query.limit()).stream()
                        .map(ServiceCredentialQueryService::metadata)
                        .toList()
                : fetched.stream().map(ServiceCredentialQueryService::metadata).toList();
        return new ServiceCredentialPage(page, hasNext);
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
