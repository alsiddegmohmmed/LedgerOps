package com.ledgerops.payment.api;

import java.util.List;

public record PaymentSearchPage(
        List<PaymentSearchItem> items,
        String nextCursor
) {

    public PaymentSearchPage {
        items = List.copyOf(items);
    }
}
