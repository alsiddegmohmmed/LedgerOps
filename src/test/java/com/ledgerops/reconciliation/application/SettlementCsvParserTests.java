package com.ledgerops.reconciliation.application;

import com.ledgerops.reconciliation.domain.SettlementValidationReasonCode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettlementCsvParserTests {

    private final SettlementCsvParser parser = new SettlementCsvParser();

    @Test
    void acceptsExactHeaderAndRfc4180QuotedFields() {
        String csv = String.join("\r\n",
                String.join(",", SettlementCsvParser.HEADER),
                "batch-1,2026-08-01,2026-08-31,payment-1,PAYMENT,payment:00000000-0000-0000-0000-000000000001,\"provider,ref\",10.00,SAR,SUCCESS,2026-08-01,2026-08-01T12:00:00Z")
                + "\r\n";
        List<List<String>> rows = new ArrayList<>();

        parser.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)),
                (rowNumber, fields) -> rows.add(List.copyOf(fields)));

        assertThat(rows).containsExactly(List.of(
                "batch-1", "2026-08-01", "2026-08-31", "payment-1", "PAYMENT",
                "payment:00000000-0000-0000-0000-000000000001", "provider,ref", "10.00",
                "SAR", "SUCCESS", "2026-08-01", "2026-08-01T12:00:00Z"));
    }

    @Test
    void rejectsHeaderChangesAndInvalidUtf8AsStructuralFailures() {
        String invalidHeader = "wrong," + String.join(",", SettlementCsvParser.HEADER.subList(1, 12)) + "\n";
        assertThatThrownBy(() -> parser.parse(
                new ByteArrayInputStream(invalidHeader.getBytes(StandardCharsets.UTF_8)), (row, fields) -> { }))
                .isInstanceOf(SettlementStructuralException.class)
                .extracting("reasonCode")
                .isEqualTo(SettlementValidationReasonCode.INVALID_HEADER);

        assertThatThrownBy(() -> parser.parse(
                new ByteArrayInputStream(new byte[]{(byte) 0xC3, (byte) 0x28}), (row, fields) -> { }))
                .isInstanceOf(SettlementStructuralException.class)
                .extracting("reasonCode")
                .isEqualTo(SettlementValidationReasonCode.INVALID_UTF8);
    }

    @Test
    void rejectsMoreThanOneHundredThousandRows() {
        StringBuilder csv = new StringBuilder(String.join(",", SettlementCsvParser.HEADER)).append('\n');
        String row = "batch-1,2026-08-01,2026-08-31,payment-1,PAYMENT,payment:00000000-0000-0000-0000-000000000001,provider-1,10.00,SAR,SUCCESS,2026-08-01,2026-08-01T12:00:00Z\n";
        csv.append(row.repeat(100_001));

        assertThatThrownBy(() -> parser.parse(
                new ByteArrayInputStream(csv.toString().getBytes(StandardCharsets.UTF_8)), (rowNumber, fields) -> { }))
                .isInstanceOf(SettlementStructuralException.class)
                .extracting("reasonCode")
                .isEqualTo(SettlementValidationReasonCode.TOO_MANY_ROWS);
    }
}
