package com.ledgerops.reporting.api;

import java.time.Instant;
import java.util.Objects;

public record OperationalSummaryPeriod(
        Instant from,
        Instant to
) {

    public OperationalSummaryPeriod {
        Objects.requireNonNull(from, "Summary period start must not be null");
        Objects.requireNonNull(to, "Summary period end must not be null");
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("Summary period start must be before its exclusive end");
        }
    }
}
