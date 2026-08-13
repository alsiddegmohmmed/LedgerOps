package com.ledgerops.reporting.api;

public record OperationalSummaryProjection(
        long generation,
        long cursor
) {

    public OperationalSummaryProjection {
        if (generation < 1) {
            throw new IllegalArgumentException("Projection generation must be positive");
        }
        if (cursor < 0) {
            throw new IllegalArgumentException("Projection cursor must not be negative");
        }
    }
}
