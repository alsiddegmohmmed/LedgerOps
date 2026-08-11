package com.ledgerops.simulator;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementFileGeneratorTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final LocalDate START = LocalDate.of(2026, 8, 1);
    private static final LocalDate END = LocalDate.of(2026, 8, 31);

    @Test
    void outputIsDeterministicAndUsesTheApprovedHeaderAndOrder() throws Exception {
        SettlementFileGenerator generator = new SettlementFileGenerator();
        SettlementFileGenerator.Transaction payment = transaction(
                "payment:00000000-0000-0000-0000-000000000001", "SIM-PAYMENT", "SUCCESS",
                "{\"amount\":\"10.00\",\"currency\":\"USD\"}",
                "{\"settlementMode\":\"EXACT\"}");
        SettlementFileGenerator.Transaction reversal = transaction(
                "reversal:00000000-0000-0000-0000-000000000002", "SIM-REVERSAL", "SUCCESS",
                "{\"amount\":\"5.00\",\"currency\":\"USD\"}",
                "{\"settlementMode\":\"EXACT\"}");
        SettlementFileGenerator.Request request = new SettlementFileGenerator.Request(
                "batch-2026-08", START, END, Map.of());

        String first = new String(generator.generate(request, List.of(reversal, payment)));
        String second = new String(generator.generate(request, List.of(payment, reversal)));

        assertEquals(first, second);
        assertTrue(first.startsWith(SettlementFileGenerator.HEADER + "\r\n"));
        assertTrue(first.contains("payment:00000000-0000-0000-0000-000000000001"));
        assertTrue(first.contains(",PAYMENT,"));
        assertTrue(first.contains(",REVERSAL,"));
    }

    @Test
    void duplicateAndMismatchScenarioSnapshotsAreDeterministic() throws Exception {
        SettlementFileGenerator.Transaction transaction = transaction(
                "payment:00000000-0000-0000-0000-000000000001", "SIM-PAYMENT", "SUCCESS",
                "{\"amount\":\"10.00\",\"currency\":\"USD\"}",
                "{\"settlementMode\":\"DUPLICATE_RECORD\"}");
        SettlementFileGenerator.Request request = new SettlementFileGenerator.Request(
                "batch-2026-08", START, END,
                Map.of(transaction.providerIdempotencyKey(),
                        new SettlementFileGenerator.Correction("11.00", null, null, null)));

        String output = new String(new SettlementFileGenerator().generate(request, List.of(transaction)));

        assertEquals(2, output.lines().filter(line -> line.contains("payment:00000000-0000-0000-0000-000000000001")).count());
        assertTrue(output.contains(",11.00,USD,SUCCESS,"));
    }

    private SettlementFileGenerator.Transaction transaction(
            String key, String reference, String category, String payload, String snapshot) throws Exception {
        JsonNode requestPayload = JSON.readTree(payload);
        JsonNode scenarioSnapshot = JSON.readTree(snapshot);
        return new SettlementFileGenerator.Transaction(
                key, reference, category, requestPayload, scenarioSnapshot,
                Instant.parse("2026-08-11T10:15:30Z"));
    }
}
