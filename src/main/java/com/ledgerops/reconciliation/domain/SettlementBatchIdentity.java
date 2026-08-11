package com.ledgerops.reconciliation.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public record SettlementBatchIdentity(
        UUID tenantId,
        String providerId,
        String providerBatchReference,
        LocalDate settlementPeriodStart,
        LocalDate settlementPeriodEnd
) {

    public SettlementBatchIdentity {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        providerId = requireVisible(providerId, "Provider ID", 64);
        providerBatchReference = requireVisible(providerBatchReference,
                "Provider batch reference", 100);
        Objects.requireNonNull(settlementPeriodStart, "Settlement period start must not be null");
        Objects.requireNonNull(settlementPeriodEnd, "Settlement period end must not be null");
        if (settlementPeriodEnd.isBefore(settlementPeriodStart)) {
            throw new IllegalArgumentException("Settlement period end must not precede start");
        }
    }

    private static String requireVisible(String value, String field, int maxLength) {
        Objects.requireNonNull(value, field + " must not be null");
        if (!value.matches("[\\x21-\\x7E]{1," + maxLength + "}")) {
            throw new IllegalArgumentException(field + " must contain visible ASCII characters only");
        }
        return value;
    }
}
