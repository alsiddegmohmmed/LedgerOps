package com.ledgerops.reporting.api;

import java.util.Objects;

public record OperationalSummarySourceLink(String href) {

    public OperationalSummarySourceLink {
        Objects.requireNonNull(href, "Summary source link must not be null");
        if (href.isBlank()) {
            throw new IllegalArgumentException("Summary source link must not be blank");
        }
    }
}
