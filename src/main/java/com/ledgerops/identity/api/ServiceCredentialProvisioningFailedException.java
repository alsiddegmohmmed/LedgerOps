package com.ledgerops.identity.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Signals that durable credential provisioning state was recorded as failed.
 * The operation identifier is safe to expose; the external failure detail is
 * supplied by Identity as a safe, non-secret message.
 */
public final class ServiceCredentialProvisioningFailedException extends RuntimeException {

    private final UUID operationId;
    private final String failureCode;

    public ServiceCredentialProvisioningFailedException(
            UUID operationId,
            String failureCode,
            String safeDetail,
            Throwable cause
    ) {
        super(safeDetail, cause);
        this.operationId = Objects.requireNonNull(operationId, "Operation ID must not be null");
        this.failureCode = Objects.requireNonNull(failureCode, "Failure code must not be null");
    }

    public UUID operationId() {
        return operationId;
    }

    public String failureCode() {
        return failureCode;
    }
}
