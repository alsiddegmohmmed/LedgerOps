package com.ledgerops.payment.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record PaymentAttempt(
        PaymentAttemptId attemptId,
        UUID tenantId,
        PaymentId paymentId,
        int sequence,
        ProviderId providerId,
        String providerIdempotencyKey,
        Instant initiatedAt,
        UUID merchantId,
        CustomerId customerId,
        Money amount,
        PaymentMethodCategory paymentMethodCategory,
        String requestIntentHash
) {
    private static final Pattern HASH = Pattern.compile("^[0-9a-f]{64}$");

    public PaymentAttempt {
        Objects.requireNonNull(attemptId, "Attempt ID must not be null");
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(paymentId, "Payment ID must not be null");
        if (sequence < 1) {
            throw new IllegalArgumentException("Payment Attempt sequence must be positive");
        }
        Objects.requireNonNull(providerId, "Provider ID must not be null");
        if (providerId != ProviderId.SIMULATOR) {
            throw new IllegalArgumentException("Release 0.2 supports only SIMULATOR");
        }
        if (providerIdempotencyKey == null || providerIdempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Provider idempotency key must not be blank");
        }
        String expectedProviderKey = "payment:" + paymentId.value().toString().toLowerCase();
        if (!providerIdempotencyKey.equals(expectedProviderKey)) {
            throw new IllegalArgumentException(
                    "Provider idempotency key must be derived from Payment ID"
            );
        }
        Objects.requireNonNull(initiatedAt, "Initiation time must not be null");
        Objects.requireNonNull(merchantId, "Merchant ID must not be null");
        Objects.requireNonNull(customerId, "Customer ID must not be null");
        Objects.requireNonNull(amount, "Amount must not be null");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Payment Attempt amount must be positive");
        }
        Objects.requireNonNull(paymentMethodCategory, "Payment method category must not be null");
        if (requestIntentHash == null || !HASH.matcher(requestIntentHash).matches()) {
            throw new IllegalArgumentException("Request-intent hash must be lowercase SHA-256");
        }
    }
}
