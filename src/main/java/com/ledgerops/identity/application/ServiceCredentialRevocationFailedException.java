package com.ledgerops.identity.application;

import com.ledgerops.identity.domain.ServiceCredentialId;

import java.util.Objects;

/**
 * Signals that local revocation was committed but external Keycloak cleanup
 * still needs to be retried.
 */
final class ServiceCredentialRevocationFailedException extends RuntimeException {
    private final ServiceCredentialId credentialId;
    private final String failureCode;

    ServiceCredentialRevocationFailedException(
            ServiceCredentialId credentialId,
            String failureCode,
            String safeDetail,
            Throwable cause
    ) {
        super(safeDetail, cause);
        this.credentialId = Objects.requireNonNull(credentialId, "Credential ID must not be null");
        this.failureCode = Objects.requireNonNull(failureCode, "Failure code must not be null");
    }

    ServiceCredentialId credentialId() {
        return credentialId;
    }

    String failureCode() {
        return failureCode;
    }
}
