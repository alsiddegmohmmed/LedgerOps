package com.ledgerops.payment.api;

import java.util.Set;

final class ReconciliationStatuses {

    private static final Set<String> SUPPORTED = Set.of(
            "NOT_APPLICABLE", "AWAITING_BATCH", "PENDING", "MATCHED", "DISCREPANCY");

    private ReconciliationStatuses() {
    }

    static boolean isSupported(String value) {
        return SUPPORTED.contains(value);
    }
}
