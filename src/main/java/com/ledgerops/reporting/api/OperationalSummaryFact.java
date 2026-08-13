package com.ledgerops.reporting.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * A published, source-owned fact used to build one Reporting generation.
 *
 * <p>This is deliberately a projection input rather than a source-module
 * entity. Reporting can receive it from an event consumer or a published
 * read boundary without taking ownership of transactional truth.</p>
 */
public record OperationalSummaryFact(
        UUID tenantId,
        OperationalSummaryMetricCode metric,
        String sourceType,
        UUID sourceId,
        UUID merchantId,
        Instant occurredAt,
        BigDecimal amount,
        String currency,
        String valueCode,
        String currentState,
        Boolean currentReconciliationRun
) {

    public OperationalSummaryFact {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(metric, "Operational-summary metric must not be null");
        if (sourceType == null || sourceType.isBlank()) {
            throw new IllegalArgumentException("Operational-summary source type must not be blank");
        }
        Objects.requireNonNull(sourceId, "Operational-summary source ID must not be null");
        Objects.requireNonNull(occurredAt, "Operational-summary occurrence time must not be null");
        if (amount != null && amount.signum() < 0) {
            throw new IllegalArgumentException("Operational-summary amount must not be negative");
        }
        if (currency != null) {
            currency = currency.trim().toUpperCase(Locale.ROOT);
            if (!currency.matches("[A-Z]{3}")) {
                throw new IllegalArgumentException("Operational-summary currency must be a three-letter code");
            }
        }
        if (metric == OperationalSummaryMetricCode.PAYMENT_VOLUME
                && (amount == null || currency == null)) {
            throw new IllegalArgumentException(
                    "Payment-volume facts require amount and currency");
        }
        if (metric == OperationalSummaryMetricCode.PROVIDER_HEALTH_EVALUATION
                && (merchantId != null || valueCode == null || valueCode.isBlank())) {
            throw new IllegalArgumentException(
                    "Provider-health facts require a state and cannot be Merchant-scoped");
        }
        if (metric == OperationalSummaryMetricCode.OPEN_DISCREPANCY
                && currentReconciliationRun == null) {
            throw new IllegalArgumentException(
                    "Discrepancy facts must identify whether they belong to the current reconciliation run");
        }
    }
}
