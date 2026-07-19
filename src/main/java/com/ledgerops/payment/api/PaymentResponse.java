package com.ledgerops.payment.api;

import com.ledgerops.payment.domain.Payment;

import java.math.BigDecimal;
import java.util.UUID;

record PaymentResponse(
        UUID id,
        UUID tenantId,
        UUID merchantId,
        UUID customerId,
        BigDecimal amount,
        String currency,
        String paymentMethodCategory,
        String status
) {

    static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.id().value(),
                payment.tenantId(),
                payment.merchantReference().value(),
                payment.customerId().value(),
                payment.amount().amount(),
                payment.amount().currency().getCurrencyCode(),
                payment.paymentMethodCategory().value(),
                payment.status().name()
        );
    }
}
