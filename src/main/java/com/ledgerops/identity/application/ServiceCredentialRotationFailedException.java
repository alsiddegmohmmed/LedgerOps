package com.ledgerops.identity.application;

import com.ledgerops.identity.domain.ServiceCredentialId;

import java.util.Objects;

/**
 * Signals that replacement activation and local old-credential revocation
 * committed, but disabling the old external client still needs a retry.
 */
final class ServiceCredentialRotationFailedException extends RuntimeException {
    private final ServiceCredentialId replacementCredentialId;
    private final ServiceCredentialId oldCredentialId;
    private final String failureCode;

    ServiceCredentialRotationFailedException(
            ServiceCredentialId replacementCredentialId,
            ServiceCredentialId oldCredentialId,
            String failureCode,
            String safeDetail,
            Throwable cause
    ) {
        super(safeDetail, cause);
        this.replacementCredentialId = Objects.requireNonNull(
                replacementCredentialId,
                "Replacement credential ID must not be null");
        this.oldCredentialId = Objects.requireNonNull(
                oldCredentialId,
                "Old credential ID must not be null");
        this.failureCode = Objects.requireNonNull(failureCode, "Failure code must not be null");
    }

    ServiceCredentialId replacementCredentialId() {
        return replacementCredentialId;
    }

    ServiceCredentialId oldCredentialId() {
        return oldCredentialId;
    }

    String failureCode() {
        return failureCode;
    }
}
