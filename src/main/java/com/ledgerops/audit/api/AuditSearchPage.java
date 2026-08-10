package com.ledgerops.audit.api;

import java.util.List;

public record AuditSearchPage(List<AuditSearchItem> items, String nextCursor) {
    public AuditSearchPage {
        items = List.copyOf(items);
    }
}
