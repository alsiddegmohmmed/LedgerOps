package com.ledgerops.payment.application;

import com.ledgerops.payment.domain.PaymentAttempt;

import java.util.UUID;

public record PaymentSubmissionResult(
        VersionedPayment payment,
        PaymentAttempt attempt,
        UUID outboxId,
        UUID messageId,
        boolean replay
) {
}
