package com.ledgerops.payment.application;

import java.time.Instant;
import java.util.UUID;

public record PaymentRetryNowResult(
        UUID paymentId,
        UUID providerWorkId,
        Instant previousDueAt,
        Instant dueAt
) {
}
