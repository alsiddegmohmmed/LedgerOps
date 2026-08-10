package com.ledgerops.payment.api;

import java.time.Instant;
import java.util.UUID;

public record PaymentNoteResponse(
        UUID noteId,
        UUID tenantId,
        UUID paymentId,
        String authorIssuer,
        String authorSubject,
        String content,
        Instant createdAt
) {
}
