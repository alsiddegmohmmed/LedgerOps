package com.ledgerops.payment.application;

import java.util.UUID;

public final class ReversalProviderResultConsistencyException extends RuntimeException {

    private final UUID reversalId;

    public ReversalProviderResultConsistencyException(UUID reversalId, String message) {
        super(message);
        this.reversalId = reversalId;
    }

    public ReversalProviderResultConsistencyException(
            UUID reversalId,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.reversalId = reversalId;
    }

    public UUID reversalId() {
        return reversalId;
    }
}
