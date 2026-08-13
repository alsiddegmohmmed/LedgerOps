package com.ledgerops.payment.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A durable final Provider outcome applied by Payment. */
public record PaymentOperationalSummaryOutcome(
        UUID paymentId,
        UUID tenantId,
        UUID merchantId,
        String finalCategory,
        Instant appliedAt
) {

    public PaymentOperationalSummaryOutcome {
        Objects.requireNonNull(paymentId, "Payment ID must not be null");
        Objects.requireNonNull(tenantId, "Payment Tenant ID must not be null");
        Objects.requireNonNull(merchantId, "Payment Merchant ID must not be null");
        Objects.requireNonNull(finalCategory, "Final Provider outcome must not be null");
        Objects.requireNonNull(appliedAt, "Provider outcome application time must not be null");
        if (!switch (finalCategory) {
            case "SUCCESS", "DECLINED", "PERMANENT_FAILURE" -> true;
            default -> false;
        }) {
            throw new IllegalArgumentException(
                    "Final Provider outcome must be SUCCESS, DECLINED, or PERMANENT_FAILURE");
        }
    }

    public boolean successful() {
        return "SUCCESS".equals(finalCategory);
    }

    public boolean failed() {
        return "DECLINED".equals(finalCategory)
                || "PERMANENT_FAILURE".equals(finalCategory);
    }
}
