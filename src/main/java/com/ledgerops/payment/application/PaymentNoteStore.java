package com.ledgerops.payment.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentNoteStore {

    Optional<PaymentResource> findPayment(UUID tenantId, UUID paymentId);

    void append(Note note);

    List<Note> findByPayment(UUID tenantId, UUID paymentId);

    record PaymentResource(UUID tenantId, UUID paymentId, UUID merchantId) {
    }

    record Note(
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
}
