package com.ledgerops.identity.infrastructure;

import com.ledgerops.identity.domain.CredentialProvisioningOperation;
import com.ledgerops.identity.domain.CredentialProvisioningOperationId;
import com.ledgerops.identity.domain.CredentialProvisioningOperationRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
class CredentialProvisioningOperationPersistenceAdapter
        implements CredentialProvisioningOperationRepository {

    private final SpringDataCredentialProvisioningOperationRepository operations;

    CredentialProvisioningOperationPersistenceAdapter(
            SpringDataCredentialProvisioningOperationRepository operations
    ) {
        this.operations = operations;
    }

    @Override
    @Transactional
    public CredentialProvisioningOperation save(CredentialProvisioningOperation operation) {
        CredentialProvisioningOperationJpaEntity entity = operations.findById(operation.id().value())
                .map(existing -> {
                    existing.updateFrom(operation);
                    return existing;
                })
                .orElseGet(() -> new CredentialProvisioningOperationJpaEntity(operation));
        return operations.saveAndFlush(entity).toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CredentialProvisioningOperation> findById(
            CredentialProvisioningOperationId operationId
    ) {
        return operations.findById(operationId.value())
                .map(CredentialProvisioningOperationJpaEntity::toDomain);
    }

    @Override
    @Transactional
    public Optional<CredentialProvisioningOperation> findByIdForUpdate(
            CredentialProvisioningOperationId operationId
    ) {
        return operations.findByIdForUpdate(operationId.value())
                .map(CredentialProvisioningOperationJpaEntity::toDomain);
    }
}
