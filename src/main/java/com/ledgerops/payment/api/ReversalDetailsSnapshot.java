package com.ledgerops.payment.api;

import com.ledgerops.payment.domain.Reversal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Read-only Reversal data published by the Payment module to operational queries.
 */
public record ReversalDetailsSnapshot(
        UUID reversalId,
        UUID tenantId,
        UUID paymentId,
        UUID merchantId,
        BigDecimal amount,
        String currency,
        String status,
        UUID requestedBy,
        String requestReason,
        Instant requestedAt,
        Instant processingAt,
        Instant failedAt,
        Instant completedAt,
        String failureCategory,
        long version
) {

    public static ReversalDetailsSnapshot from(Reversal reversal) {
        return new ReversalDetailsSnapshot(
                reversal.id().value(),
                reversal.tenantId(),
                reversal.paymentId().value(),
                reversal.merchantId(),
                reversal.amount().amount(),
                reversal.amount().currency().getCurrencyCode(),
                reversal.status().name(),
                reversal.requestedBy(),
                reversal.requestReason(),
                reversal.requestedAt(),
                reversal.processingAt(),
                reversal.failedAt(),
                reversal.completedAt(),
                reversal.failureCategory(),
                reversal.version()
        );
    }
}
