package com.ledgerops.identity.infrastructure;

import com.ledgerops.identity.domain.ServiceCredential;
import com.ledgerops.identity.domain.ServiceCredentialId;
import com.ledgerops.identity.domain.ServiceCredentialRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
class ServiceCredentialPersistenceAdapter implements ServiceCredentialRepository {

    private final SpringDataServiceCredentialRepository credentials;

    ServiceCredentialPersistenceAdapter(SpringDataServiceCredentialRepository credentials) {
        this.credentials = credentials;
    }

    @Override
    @Transactional
    public ServiceCredential save(ServiceCredential credential) {
        ServiceCredentialJpaEntity entity = credentials.findById(credential.id().value())
                .map(existing -> {
                    existing.updateFrom(credential);
                    return existing;
                })
                .orElseGet(() -> new ServiceCredentialJpaEntity(credential));
        return credentials.saveAndFlush(entity).toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ServiceCredential> findById(ServiceCredentialId credentialId) {
        return credentials.findById(credentialId.value()).map(ServiceCredentialJpaEntity::toDomain);
    }

    @Override
    @Transactional
    public Optional<ServiceCredential> findByIdForUpdate(ServiceCredentialId credentialId) {
        return credentials.findByIdForUpdate(credentialId.value()).map(ServiceCredentialJpaEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ServiceCredential> findByClientId(String keycloakClientId) {
        return credentials.findByClientId(keycloakClientId).map(ServiceCredentialJpaEntity::toDomain);
    }
}
