package com.ledgerops.identity.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Signals that local revocation committed but external cleanup still needs a
 * retry.
 */
public final class ServiceCredentialRevocationFailedException extends RuntimeException {

    private final UUID credentialId;
    private final String failureCode;

    public ServiceCredentialRevocationFailedException(
            UUID credentialId,
            String failureCode,
            String safeDetail,
            Throwable cause
    ) {
        super(safeDetail, cause);
        this.credentialId = Objects.requireNonNull(credentialId, "Credential ID must not be null");
        this.failureCode = Objects.requireNonNull(failureCode, "Failure code must not be null");
    }

    public UUID credentialId() {
        return credentialId;
    }

    public String failureCode() {
        return failureCode;
    }
}
