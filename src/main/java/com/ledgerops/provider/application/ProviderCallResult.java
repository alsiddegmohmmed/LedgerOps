package com.ledgerops.provider.application;

import com.ledgerops.provider.api.ProviderResultCategory;
import com.ledgerops.provider.api.RetryDisposition;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record ProviderCallResult(
        UUID requestId,
        UUID providerResultId,
        String providerReference,
        ProviderResultCategory category,
        RetryDisposition disposition,
        boolean providerTransactionFound,
        boolean noAcceptanceProven,
        Integer httpStatus,
        String requestBodyHash,
        String responseBodyHash,
        String communicationOutcome,
        long latencyMillis,
        String safeErrorCode,
        Instant startedAt,
        Instant completedAt
) {
    private static final Pattern HASH = Pattern.compile("^[0-9a-f]{64}$");

    public ProviderCallResult {
        Objects.requireNonNull(requestId, "Request ID must not be null");
        Objects.requireNonNull(providerResultId, "Provider result ID must not be null");
        Objects.requireNonNull(category, "Provider result category must not be null");
        Objects.requireNonNull(disposition, "Retry disposition must not be null");
        Objects.requireNonNull(startedAt, "Started-at time must not be null");
        Objects.requireNonNull(completedAt, "Completed-at time must not be null");
        if (completedAt.isBefore(startedAt) || latencyMillis < 0) {
            throw new IllegalArgumentException("Provider call timing is invalid");
        }
        if (requestBodyHash == null || !HASH.matcher(requestBodyHash).matches()
                || responseBodyHash != null && !HASH.matcher(responseBodyHash).matches()) {
            throw new IllegalArgumentException("Provider evidence hash is invalid");
        }
        if (communicationOutcome == null || !java.util.Set.of(
                "RESPONSE", "TIMEOUT", "CONNECTION_FAILURE", "CIRCUIT_OPEN",
                "BULKHEAD_FULL").contains(communicationOutcome)) {
            throw new IllegalArgumentException("Provider communication outcome is invalid");
        }
        RetryDisposition required = switch (category) {
            case SUCCESS, DECLINED, PERMANENT_FAILURE -> RetryDisposition.NOT_RETRYABLE;
            case ACCEPTED, PENDING, UNKNOWN -> RetryDisposition.STATUS_RECOVERY_REQUIRED;
            case TEMPORARY_FAILURE -> noAcceptanceProven && !providerTransactionFound
                    ? RetryDisposition.SAFE_TO_RESUBMIT
                    : RetryDisposition.STATUS_RECOVERY_REQUIRED;
        };
        if (disposition != required) {
            throw new IllegalArgumentException(
                    "Provider result category and retry disposition are inconsistent");
        }
        if (noAcceptanceProven && category != ProviderResultCategory.TEMPORARY_FAILURE) {
            throw new IllegalArgumentException(
                    "No-acceptance proof applies only to a safe temporary failure");
        }
        if (noAcceptanceProven && providerTransactionFound) {
            throw new IllegalArgumentException(
                    "Provider acceptance cannot be both disproven and found");
        }
    }
}
