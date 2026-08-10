package com.ledgerops.payment.api;

import java.time.Instant;
import java.util.UUID;

public record PaymentNoteSnapshot(
        UUID noteId,
        UUID tenantId,
        UUID paymentId,
        UUID merchantId,
        String authorIssuer,
        String authorSubject,
        String content,
        Instant createdAt
) {
}
