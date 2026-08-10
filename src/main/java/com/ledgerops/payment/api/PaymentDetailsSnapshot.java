package com.ledgerops.payment.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PaymentDetailsSnapshot(
        UUID paymentId,
        UUID tenantId,
        UUID merchantId,
        UUID customerId,
        BigDecimal amount,
        String currency,
        String paymentMethodCategory,
        String state,
        Instant createdAt,
        Instant updatedAt,
        List<PaymentAttemptSnapshot> attempts,
        List<PaymentNoteSnapshot> notes
) {

    public PaymentDetailsSnapshot {
        attempts = List.copyOf(attempts);
        notes = List.copyOf(notes);
    }
}
