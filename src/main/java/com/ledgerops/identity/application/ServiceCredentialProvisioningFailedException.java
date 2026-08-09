package com.ledgerops.identity.application;

import com.ledgerops.identity.domain.CredentialProvisioningOperationId;

import java.util.Objects;

final class ServiceCredentialProvisioningFailedException extends RuntimeException {
    private final CredentialProvisioningOperationId operationId;
    private final String failureCode;

    ServiceCredentialProvisioningFailedException(
            CredentialProvisioningOperationId operationId,
            String failureCode,
            String safeDetail,
            Throwable cause
    ) {
        super(safeDetail, cause);
        this.operationId = Objects.requireNonNull(operationId, "Operation ID must not be null");
        this.failureCode = Objects.requireNonNull(failureCode, "Failure code must not be null");
    }

    CredentialProvisioningOperationId operationId() {
        return operationId;
    }

    String failureCode() {
        return failureCode;
    }
}
