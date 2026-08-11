package com.ledgerops.simulator;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class SettlementFileGenerator {

    static final String HEADER = String.join(",",
            "providerBatchReference",
            "settlementPeriodStart",
            "settlementPeriodEnd",
            "providerRecordKey",
            "operationType",
            "providerIdempotencyKey",
            "providerReference",
            "amount",
            "currency",
            "transactionStatus",
            "settlementDate",
            "providerEventTime");

    byte[] generate(Request request, List<Transaction> transactions) {
        List<Row> rows = new ArrayList<>();
        for (Transaction transaction : transactions) {
            String mode = transaction.scenarioSnapshot() == null
                    ? "EXACT"
                    : text(transaction.scenarioSnapshot(), "settlementMode", "EXACT");
            if ("MISSING".equals(mode)) continue;

            Row row = row(request, transaction, request.correctionFor(transaction.providerIdempotencyKey()));
            rows.add(row);
            if ("DUPLICATE_RECORD".equals(mode)) rows.add(row);
        }
        rows.sort(Comparator.comparing(Row::providerRecordKey));
        StringBuilder csv = new StringBuilder(HEADER).append("\r\n");
        for (Row row : rows) {
            csv.append(row.values().stream().map(SettlementFileGenerator::csv).reduce(",", (left, right) ->
                    left.isEmpty() ? right : left + "," + right)).append("\r\n");
        }
        return csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private Row row(Request request, Transaction transaction, Correction correction) {
        JsonNode payload = transaction.requestPayload();
        String key = transaction.providerIdempotencyKey();
        String operationType = key.startsWith("reversal:") ? "REVERSAL" : "PAYMENT";
        String amount = text(payload, "amount", "0");
        String currency = text(payload, "currency", "USD");
        String status = settlementStatus(operationType, transaction.resultCategory());
        LocalDate settlementDate = request.settlementPeriodEnd();
        String mode = transaction.scenarioSnapshot() == null
                ? "EXACT" : text(transaction.scenarioSnapshot(), "settlementMode", "EXACT");
        switch (mode) {
            case "AMOUNT_MISMATCH" -> amount = mismatchAmount(amount, currency);
            case "CURRENCY_MISMATCH" -> currency = "USD".equals(currency) ? "EUR" : "USD";
            case "STATUS_MISMATCH" -> status = "SUCCESS".equals(status) ? "FAILED" : "SUCCESS";
            case "DATE_MISMATCH" -> settlementDate = request.settlementPeriodStart().minusDays(1);
            case "EXACT", "DUPLICATE_RECORD", "MISSING" -> { }
            default -> throw new IllegalStateException("Unsupported settlement mode: " + mode);
        }
        if (correction != null) {
            if (correction.amount() != null) amount = correction.amount();
            if (correction.currency() != null) currency = correction.currency();
            if (correction.transactionStatus() != null) status = correction.transactionStatus();
            if (correction.settlementDate() != null) settlementDate = correction.settlementDate();
        }
        Instant eventTime = transaction.createdAt().truncatedTo(ChronoUnit.SECONDS);
        return new Row(List.of(
                request.providerBatchReference(),
                request.settlementPeriodStart().toString(),
                request.settlementPeriodEnd().toString(),
                key,
                operationType,
                key,
                transaction.providerReference(),
                amount,
                currency,
                status,
                settlementDate.toString(),
                DateTimeFormatter.ISO_INSTANT.format(eventTime)
        ), key);
    }

    private String settlementStatus(String operationType, String resultCategory) {
        if ("REVERSAL".equals(operationType)) {
            return "SUCCESS".equals(resultCategory) ? "REVERSED" : "FAILED";
        }
        return "SUCCESS".equals(resultCategory) ? "SUCCESS" : "FAILED";
    }

    private String mismatchAmount(String value, String currency) {
        int fractionDigits = java.util.Currency.getInstance(currency).getDefaultFractionDigits();
        BigDecimal increment = BigDecimal.ONE.movePointLeft(Math.max(fractionDigits, 0));
        return new BigDecimal(value).add(increment)
                .setScale(Math.max(fractionDigits, 0), RoundingMode.UNNECESSARY)
                .toPlainString();
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || !value.isString() || value.asString().isBlank()
                ? fallback : value.asString();
    }

    private static String csv(String value) {
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0
                && value.indexOf('\r') < 0 && value.indexOf('\n') < 0) return value;
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    record Request(
            String providerBatchReference,
            LocalDate settlementPeriodStart,
            LocalDate settlementPeriodEnd,
            java.util.Map<String, Correction> corrections
    ) {
        Correction correctionFor(String providerIdempotencyKey) {
            return corrections == null ? null : corrections.get(providerIdempotencyKey);
        }
    }

    record Transaction(
            String providerIdempotencyKey,
            String providerReference,
            String resultCategory,
            JsonNode requestPayload,
            JsonNode scenarioSnapshot,
            Instant createdAt
    ) {
    }

    record Correction(
            String amount,
            String currency,
            String transactionStatus,
            LocalDate settlementDate
    ) {
    }

    private record Row(List<String> values, String providerRecordKey) {
    }
}
