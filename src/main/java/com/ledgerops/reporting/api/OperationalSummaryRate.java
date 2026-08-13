package com.ledgerops.reporting.api;

import java.math.BigDecimal;
import java.util.Objects;

public record OperationalSummaryRate(
        long numerator,
        long denominator,
        BigDecimal rate,
        OperationalSummarySourceLink numeratorSource,
        OperationalSummarySourceLink denominatorSource
) {

    public OperationalSummaryRate {
        if (numerator < 0 || denominator < 0 || numerator > denominator) {
            throw new IllegalArgumentException("Summary rate counts are invalid");
        }
        if (denominator == 0 && rate != null) {
            throw new IllegalArgumentException("A zero-denominator summary rate must be null");
        }
        if (denominator > 0 && (rate == null || rate.signum() < 0
                || rate.compareTo(BigDecimal.ONE) > 0)) {
            throw new IllegalArgumentException("A measured summary rate must be between zero and one");
        }
        Objects.requireNonNull(numeratorSource, "Summary numerator source must not be null");
        Objects.requireNonNull(denominatorSource, "Summary denominator source must not be null");
    }
}
