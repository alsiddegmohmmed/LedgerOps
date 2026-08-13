package com.ledgerops.reporting.api;

import java.util.Objects;

public record OperationalSummaryMetrics(
        OperationalSummaryPaymentVolume paymentVolume,
        OperationalSummaryRate paymentSuccessRate,
        OperationalSummaryRate paymentFailureRate,
        OperationalSummaryCount manualReviewCount,
        OperationalSummaryCount openDiscrepancyCount,
        OperationalSummaryCount unresolvedCaseCount,
        OperationalSummaryProviderHealth providerHealth
) {

    public OperationalSummaryMetrics {
        Objects.requireNonNull(paymentVolume, "Payment volume metric must not be null");
        Objects.requireNonNull(paymentSuccessRate, "Payment success metric must not be null");
        Objects.requireNonNull(paymentFailureRate, "Payment failure metric must not be null");
        Objects.requireNonNull(manualReviewCount, "Manual review metric must not be null");
        Objects.requireNonNull(openDiscrepancyCount, "Open discrepancy metric must not be null");
        Objects.requireNonNull(unresolvedCaseCount, "Unresolved Case metric must not be null");
        Objects.requireNonNull(providerHealth, "Provider health metric must not be null");
    }
}
