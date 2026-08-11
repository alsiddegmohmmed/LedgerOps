package com.ledgerops.payment.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

public record PaymentReconciliationSubject(
        UUID tenantId,
        ReconciliationSubjectType subjectType,
        UUID subjectId,
        UUID paymentId,
        UUID merchantId,
        BigDecimal amount,
        Currency currency,
        String providerId,
        String providerIdempotencyKey,
        UUID providerEvidenceId,
        UUID providerResultId,
        String providerReference,
        String financialStatus,
        Instant appliedAt
) {

    public PaymentReconciliationSubject {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(subjectType, "Subject type must not be null");
        Objects.requireNonNull(subjectId, "Subject ID must not be null");
        Objects.requireNonNull(paymentId, "Payment ID must not be null");
        Objects.requireNonNull(merchantId, "Merchant ID must not be null");
        Objects.requireNonNull(amount, "Amount must not be null");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        Objects.requireNonNull(currency, "Currency must not be null");
        requireText(providerId, "Provider ID");
        requireText(providerIdempotencyKey, "Provider idempotency key");
        Objects.requireNonNull(providerEvidenceId, "Provider evidence ID must not be null");
        Objects.requireNonNull(providerResultId, "Provider result ID must not be null");
        if (providerReference != null && providerReference.isBlank()) {
            throw new IllegalArgumentException("Provider reference must not be blank");
        }
        requireText(financialStatus, "Financial status");
        Objects.requireNonNull(appliedAt, "Applied-at time must not be null");
    }

    private static void requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
