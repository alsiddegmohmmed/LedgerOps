package com.ledgerops.payment.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A logical Payment fact used by the rebuildable Reporting projection. */
public record PaymentOperationalSummaryPayment(
        UUID paymentId,
        UUID tenantId,
        UUID merchantId,
        BigDecimal amount,
        String currency,
        Instant createdAt
) {

    public PaymentOperationalSummaryPayment {
        Objects.requireNonNull(paymentId, "Payment ID must not be null");
        Objects.requireNonNull(tenantId, "Payment Tenant ID must not be null");
        Objects.requireNonNull(merchantId, "Payment Merchant ID must not be null");
        Objects.requireNonNull(amount, "Payment amount must not be null");
        Objects.requireNonNull(currency, "Payment currency must not be null");
        Objects.requireNonNull(createdAt, "Payment creation time must not be null");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Payment amount must be positive");
        }
        if (!currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Payment currency must be an uppercase ISO code");
        }
    }
}
