package com.ledgerops.reporting.api;

import java.util.Objects;

public record OperationalSummaryCount(
        long count,
        OperationalSummarySourceLink source
) {

    public OperationalSummaryCount {
        if (count < 0) {
            throw new IllegalArgumentException("Summary count must not be negative");
        }
        Objects.requireNonNull(source, "Summary count source must not be null");
    }
}
