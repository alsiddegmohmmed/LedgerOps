package com.ledgerops.reconciliation.api;

import java.util.List;

public record SettlementBatchPage(
        List<SettlementBatchHttpResponse> items
) {
    public SettlementBatchPage {
        items = List.copyOf(items);
    }
}
