package com.ledgerops.reconciliation.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettlementRecordTests {

    @Test
    void normalizesCurrencyScaleAndProducesStableContentHash() {
        SettlementRecord first = valid(List.of(
                "batch-1", "2026-08-01", "2026-08-31", "record-1", "PAYMENT",
                "payment:00000000-0000-0000-0000-000000000001", "provider-1", "10.0", "SAR",
                "SUCCESS", "2026-08-01", "2026-08-01T12:00:00Z"));
        SettlementRecord second = valid(List.of(
                "batch-1", "2026-08-01", "2026-08-31", "record-1", "PAYMENT",
                "payment:00000000-0000-0000-0000-000000000001", "provider-1", "10.00", "SAR",
                "SUCCESS", "2026-08-01", "2026-08-01T12:00:00Z"));

        assertThat(first.amount()).isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(first.amount().scale()).isEqualTo(2);
        assertThat(first.normalizedContentHash()).isEqualTo(second.normalizedContentHash());
    }

    @Test
    void rejectsInvalidOperationKeyCurrencyPrecisionAndSettlementDate() {
        assertThatThrownBy(() -> valid(fieldsWith("operationType", "REVERSAL")))
                .isInstanceOf(SettlementRecordValidationException.class);
        assertThatThrownBy(() -> valid(fieldsWith("currency", "ZZZ")))
                .isInstanceOf(SettlementRecordValidationException.class);
        assertThatThrownBy(() -> valid(fieldsWith("amount", "10.001")))
                .isInstanceOf(SettlementRecordValidationException.class);
        assertThatThrownBy(() -> valid(fieldsWith("settlementDate", "2026-09-01")))
                .isInstanceOf(SettlementRecordValidationException.class);
    }

    private SettlementRecord valid(List<String> fields) {
        return SettlementRecord.fromFields(fields);
    }

    private List<String> fieldsWith(String name, String value) {
        List<String> fields = new java.util.ArrayList<>(List.of(
                "batch-1", "2026-08-01", "2026-08-31", "record-1", "PAYMENT",
                "payment:00000000-0000-0000-0000-000000000001", "provider-1", "10.00", "SAR",
                "SUCCESS", "2026-08-01", "2026-08-01T12:00:00Z"));
        int index = switch (name) {
            case "operationType" -> 4;
            case "amount" -> 7;
            case "currency" -> 8;
            case "settlementDate" -> 10;
            default -> throw new IllegalArgumentException(name);
        };
        fields.set(index, value);
        return fields;
    }
}
