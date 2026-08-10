package com.ledgerops.casework.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record CaseHistoryEntry(
        long sequence,
        String eventType,
        CaseStatus fromStatus,
        CaseStatus toStatus,
        UUID actorId,
        String reason,
        Instant occurredAt
) {
    public CaseHistoryEntry {
        if (sequence < 1) throw new IllegalArgumentException("History sequence must be positive");
        Objects.requireNonNull(eventType, "History event type must not be null");
        Objects.requireNonNull(actorId, "History actor must not be null");
        Objects.requireNonNull(occurredAt, "History time must not be null");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("History reason must not be blank");
    }
}
