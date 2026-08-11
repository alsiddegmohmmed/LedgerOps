package com.ledgerops.payment.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record PaymentReconciliationPage(
        List<PaymentReconciliationSubject> subjects,
        ReconciliationSubjectCursor next
) {

    public PaymentReconciliationPage {
        subjects = List.copyOf(Objects.requireNonNull(subjects, "Subjects must not be null"));
        if (next != null && subjects.isEmpty()) {
            throw new IllegalArgumentException("A non-empty page is required for a next cursor");
        }
    }

    public Optional<ReconciliationSubjectCursor> nextCursor() {
        return Optional.ofNullable(next);
    }
}
