package com.ledgerops.payment.application;

import com.ledgerops.payment.api.PaymentSearchQuery;
import com.ledgerops.payment.domain.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PaymentSearchStore {

    Batch findBatch(
            PaymentSearchQuery query,
            Instant cursorCreatedAt,
            UUID cursorPaymentId,
            int limit
    );

    record Batch(List<Row> rows, boolean hasMore) {
        public Batch {
            rows = List.copyOf(rows);
        }
    }

    record Row(
            UUID paymentId,
            UUID tenantId,
            UUID merchantReference,
            UUID customerId,
            BigDecimal amount,
            String currency,
            PaymentStatus state,
            Instant createdAt,
            Instant updatedAt
    ) {
    }
}
