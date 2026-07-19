package com.ledgerops.payment.application;

import java.math.BigDecimal;
import java.util.UUID;

public record CreatePaymentCommand(
        UUID tenantId,
        UUID merchantId,
        UUID customerId,
        BigDecimal amount,
        String currency,
        String paymentMethodCategory,
        String idempotencyKey
) {
}
