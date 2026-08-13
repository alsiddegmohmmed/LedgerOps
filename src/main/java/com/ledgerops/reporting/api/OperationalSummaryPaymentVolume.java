package com.ledgerops.reporting.api;

import java.util.List;
import java.util.Objects;

public record OperationalSummaryPaymentVolume(
        long paymentCount,
        List<OperationalSummaryAmount> amountByCurrency,
        OperationalSummarySourceLink source
) {

    public OperationalSummaryPaymentVolume {
        if (paymentCount < 0) {
            throw new IllegalArgumentException("Payment count must not be negative");
        }
        amountByCurrency = List.copyOf(Objects.requireNonNull(amountByCurrency,
                "Amounts by currency must not be null"));
        source = Objects.requireNonNull(source, "Payment volume source must not be null");
    }
}
