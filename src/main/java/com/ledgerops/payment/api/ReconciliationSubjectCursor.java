package com.ledgerops.payment.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ReconciliationSubjectCursor(
        Instant appliedAt,
        UUID subjectId,
        ReconciliationSubjectType subjectType
) {

    public ReconciliationSubjectCursor {
        Objects.requireNonNull(appliedAt, "Applied-at cursor must not be null");
        Objects.requireNonNull(subjectId, "Subject cursor ID must not be null");
        Objects.requireNonNull(subjectType, "Subject cursor type must not be null");
    }
}
