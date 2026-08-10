package com.ledgerops.payment.api;

import com.ledgerops.payment.application.ReversalRetryResult;

import java.util.UUID;

record ReversalRetryResponse(
        UUID reversalId,
        UUID paymentId,
        UUID attemptId,
        int attemptSequence,
        String status,
        boolean replay
) {
    static ReversalRetryResponse from(UUID reversalId, ReversalRetryResult result) {
        return new ReversalRetryResponse(
                reversalId,
                result.attempt().paymentId().value(),
                result.attempt().attemptId().value(),
                result.attempt().sequence(),
                "PROCESSING",
                result.replay()
        );
    }
}
