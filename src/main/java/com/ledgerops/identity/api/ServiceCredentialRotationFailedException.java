package com.ledgerops.identity.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Signals that local rotation committed but disabling the old external
 * credential still requires a retry.
 */
public final class ServiceCredentialRotationFailedException extends RuntimeException {

    private final UUID replacementCredentialId;
    private final UUID oldCredentialId;
    private final String failureCode;

    public ServiceCredentialRotationFailedException(
            UUID replacementCredentialId,
            UUID oldCredentialId,
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

    public UUID replacementCredentialId() {
        return replacementCredentialId;
    }

    public UUID oldCredentialId() {
        return oldCredentialId;
    }

    public String failureCode() {
        return failureCode;
    }
}
