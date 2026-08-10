package com.ledgerops.payment.application;

import com.ledgerops.provider.api.ProviderResultCategory;
import com.ledgerops.provider.api.ProviderOperationType;
import com.ledgerops.provider.api.RetryDisposition;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PaymentProviderResultCommand(
        UUID messageId,
        UUID tenantId,
        UUID paymentId,
        ProviderOperationType operationType,
        UUID operationId,
        UUID attemptId,
        UUID providerEvidenceId,
        UUID providerResultId,
        String providerId,
        String providerIdempotencyKey,
        ProviderResultCategory category,
        RetryDisposition retryDisposition,
        String providerReference,
        String evidenceOrigin,
        Instant observedAt,
        UUID correlationId
) {
    public PaymentProviderResultCommand(
            UUID messageId,
            UUID tenantId,
            UUID paymentId,
            UUID attemptId,
            UUID providerEvidenceId,
            UUID providerResultId,
            String providerId,
            String providerIdempotencyKey,
            ProviderResultCategory category,
            RetryDisposition retryDisposition,
            String providerReference,
            String evidenceOrigin,
            Instant observedAt,
            UUID correlationId
    ) {
        this(
                messageId,
                tenantId,
                paymentId,
                ProviderOperationType.PAYMENT,
                paymentId,
                attemptId,
                providerEvidenceId,
                providerResultId,
                providerId,
                providerIdempotencyKey,
                category,
                retryDisposition,
                providerReference,
                evidenceOrigin,
                observedAt,
                correlationId
        );
    }

    public PaymentProviderResultCommand {
        Objects.requireNonNull(messageId, "Message ID must not be null");
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(paymentId, "Payment ID must not be null");
        Objects.requireNonNull(operationType, "Provider operation type must not be null");
        Objects.requireNonNull(operationId, "Provider operation ID must not be null");
        Objects.requireNonNull(attemptId, "Attempt ID must not be null");
        Objects.requireNonNull(providerEvidenceId, "Provider evidence ID must not be null");
        Objects.requireNonNull(providerResultId, "Provider result ID must not be null");
        providerId = requireText(providerId, "Provider ID");
        providerIdempotencyKey = requireText(
                providerIdempotencyKey,
                "Provider idempotency key"
        );
        Objects.requireNonNull(category, "Provider result category must not be null");
        Objects.requireNonNull(retryDisposition, "Retry disposition must not be null");
        evidenceOrigin = requireText(evidenceOrigin, "Evidence origin");
        Objects.requireNonNull(observedAt, "Observed-at time must not be null");
        Objects.requireNonNull(correlationId, "Correlation ID must not be null");
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }
}
