package com.ledgerops.reporting.api;

import java.util.List;
import java.util.Objects;

public record OperationalSummaryRecordPage(
        List<OperationalSummaryRecord> items,
        String nextAfter
) {

    public OperationalSummaryRecordPage {
        items = List.copyOf(Objects.requireNonNull(items, "Summary records must not be null"));
        if (nextAfter != null && nextAfter.isBlank()) {
            throw new IllegalArgumentException("Summary next cursor must not be blank");
        }
    }
}
