package com.ledgerops.casework.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record CaseNote(UUID noteId, UUID authorId, String text, Instant createdAt) {
    public CaseNote {
        Objects.requireNonNull(noteId, "Note ID must not be null");
        Objects.requireNonNull(authorId, "Note author must not be null");
        Objects.requireNonNull(createdAt, "Note time must not be null");
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Case note must not be blank");
    }
}
