package com.ledgerops.provider.api;

import java.time.Instant;
import java.util.UUID;

public record ProviderRecoveryOperation(
        UUID evidenceId,
        UUID workId,
        UUID attemptId,
        ProviderOperationType operationType,
        UUID operationId,
        UUID retryRequestId,
        String workStatus,
        String resultCategory,
        String retryDisposition,
        boolean providerTransactionFound,
        boolean noAcceptanceProven,
        Instant dueAt,
        Instant requestedAt,
        Instant observedAt
) {

    public ProviderRecoveryOperation(
            UUID evidenceId,
            UUID workId,
            UUID attemptId,
            UUID retryRequestId,
            String workStatus,
            String resultCategory,
            String retryDisposition,
            boolean providerTransactionFound,
            boolean noAcceptanceProven,
            Instant dueAt,
            Instant requestedAt,
            Instant observedAt
    ) {
        this(evidenceId, workId, attemptId, ProviderOperationType.PAYMENT, null,
                retryRequestId, workStatus, resultCategory, retryDisposition,
                providerTransactionFound, noAcceptanceProven, dueAt, requestedAt,
                observedAt);
    }
}
