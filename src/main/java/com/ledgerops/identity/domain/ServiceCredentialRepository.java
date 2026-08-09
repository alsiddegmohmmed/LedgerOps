package com.ledgerops.identity.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServiceCredentialRepository {

    ServiceCredential save(ServiceCredential credential);

    Optional<ServiceCredential> findById(ServiceCredentialId credentialId);

    Optional<ServiceCredential> findByIdForUpdate(ServiceCredentialId credentialId);

    Optional<ServiceCredential> findByClientId(String keycloakClientId);

    List<ServiceCredential> findPage(
            UUID tenantId,
            UUID merchantId,
            ServiceCredentialStatus status,
            Instant beforeCreatedAt,
            ServiceCredentialId beforeCredentialId,
            int limit
    );
}
