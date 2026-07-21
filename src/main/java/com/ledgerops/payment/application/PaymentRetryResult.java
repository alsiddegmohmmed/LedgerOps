package com.ledgerops.payment.application;

import com.ledgerops.payment.domain.PaymentAttempt;

import java.util.UUID;

public record PaymentRetryResult(
        PaymentAttempt attempt,
        UUID outboxId,
        UUID messageId,
        boolean replay
) {
}
