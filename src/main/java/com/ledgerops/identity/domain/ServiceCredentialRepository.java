package com.ledgerops.identity.domain;

import java.util.Optional;

public interface ServiceCredentialRepository {

    ServiceCredential save(ServiceCredential credential);

    Optional<ServiceCredential> findById(ServiceCredentialId credentialId);

    Optional<ServiceCredential> findByIdForUpdate(ServiceCredentialId credentialId);

    Optional<ServiceCredential> findByClientId(String keycloakClientId);
}
