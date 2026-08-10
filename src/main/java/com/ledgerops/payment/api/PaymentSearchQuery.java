package com.ledgerops.payment.api;

import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.payment.domain.PaymentStatus;
import com.ledgerops.risk.api.RiskDecision;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record PaymentSearchQuery(
        UUID tenantId,
        UUID paymentId,
        UUID merchantReference,
        String providerId,
        UUID customerId,
        Instant fromInclusive,
        Instant toExclusive,
        BigDecimal minimumAmount,
        BigDecimal maximumAmount,
        PaymentStatus state,
        RiskDecision riskDecision,
        String reconciliationStatus,
        int limit,
        String cursor,
        AuthorizedRequestContext authorization
) {

    public static final int MAXIMUM_LIMIT = 100;

    public PaymentSearchQuery {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(authorization, "Authorization context must not be null");
        if (limit < 1 || limit > MAXIMUM_LIMIT) {
            throw new IllegalArgumentException(
                    "Payment page limit must be between 1 and " + MAXIMUM_LIMIT);
        }
        if (fromInclusive != null && toExclusive != null
                && !fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException(
                    "Payment search start must be before its exclusive end");
        }
        if (minimumAmount != null && minimumAmount.signum() < 0) {
            throw new IllegalArgumentException("Minimum Payment amount must not be negative");
        }
        if (maximumAmount != null && maximumAmount.signum() < 0) {
            throw new IllegalArgumentException("Maximum Payment amount must not be negative");
        }
        if (minimumAmount != null && maximumAmount != null
                && minimumAmount.compareTo(maximumAmount) > 0) {
            throw new IllegalArgumentException(
                    "Minimum Payment amount must not exceed its maximum");
        }
        if (providerId != null) {
            providerId = providerId.trim().toUpperCase(Locale.ROOT);
            if (providerId.isBlank()) {
                providerId = null;
            }
        }
        if (reconciliationStatus != null) {
            reconciliationStatus = reconciliationStatus.trim().toUpperCase(Locale.ROOT);
            if (reconciliationStatus.isBlank()) {
                reconciliationStatus = null;
            } else if (!ReconciliationStatuses.isSupported(reconciliationStatus)) {
                throw new IllegalArgumentException("Unsupported reconciliation status filter");
            }
        }
        if (cursor != null) {
            cursor = cursor.trim();
            if (cursor.isBlank()) {
                cursor = null;
            }
        }
    }
}
