package com.ledgerops.payment.application;

import java.util.UUID;

public final class ProviderEvidenceUnavailableException extends RuntimeException {

    private final UUID evidenceId;

    public ProviderEvidenceUnavailableException(UUID evidenceId) {
        super("Provider evidence is not yet available: " + evidenceId);
        this.evidenceId = evidenceId;
    }

    public UUID evidenceId() {
        return evidenceId;
    }
}
