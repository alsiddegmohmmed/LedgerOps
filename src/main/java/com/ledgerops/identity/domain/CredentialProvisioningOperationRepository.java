package com.ledgerops.identity.domain;

import java.util.Optional;

public interface CredentialProvisioningOperationRepository {

    CredentialProvisioningOperation save(CredentialProvisioningOperation operation);

    Optional<CredentialProvisioningOperation> findById(
            CredentialProvisioningOperationId operationId
    );

    Optional<CredentialProvisioningOperation> findByIdForUpdate(
            CredentialProvisioningOperationId operationId
    );
}
