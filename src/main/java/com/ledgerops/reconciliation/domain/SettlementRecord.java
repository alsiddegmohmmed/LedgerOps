package com.ledgerops.reconciliation.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Currency;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record SettlementRecord(
        String providerBatchReference,
        LocalDate settlementPeriodStart,
        LocalDate settlementPeriodEnd,
        String providerRecordKey,
        SettlementOperationType operationType,
        String providerIdempotencyKey,
        String providerReference,
        BigDecimal amount,
        String currency,
        SettlementTransactionStatus transactionStatus,
        LocalDate settlementDate,
        Instant providerEventTime
) {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    public static SettlementRecord fromFields(List<String> fields) {
        if (fields == null || fields.size() != 12) {
            throw new SettlementRecordValidationException(
                    SettlementValidationReasonCode.INVALID_FIELD,
                    "Settlement row must contain exactly twelve fields");
        }
        try {
            SettlementOperationType operationType = SettlementOperationType.valueOf(fields.get(4));
            SettlementTransactionStatus transactionStatus = SettlementTransactionStatus.valueOf(fields.get(9));
            if (!fields.get(7).matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")) {
                throw new SettlementRecordValidationException(
                        SettlementValidationReasonCode.INVALID_FIELD,
                        "Amount must be a positive plain decimal string");
            }
            return new SettlementRecord(
                    fields.get(0),
                    LocalDate.parse(fields.get(1), DATE),
                    LocalDate.parse(fields.get(2), DATE),
                    fields.get(3),
                    operationType,
                    fields.get(5),
                    fields.get(6),
                    new BigDecimal(fields.get(7)),
                    fields.get(8),
                    transactionStatus,
                    LocalDate.parse(fields.get(10), DATE),
                    Instant.parse(fields.get(11))
            );
        } catch (SettlementRecordValidationException exception) {
            throw exception;
        } catch (DateTimeParseException | IllegalArgumentException exception) {
            SettlementValidationReasonCode reason = exception.getMessage() != null
                    && exception.getMessage().contains("Unsupported currency")
                    ? SettlementValidationReasonCode.UNSUPPORTED_CURRENCY
                    : SettlementValidationReasonCode.INVALID_FIELD;
            throw new SettlementRecordValidationException(reason, "Settlement row contains an invalid field");
        }
    }

    public SettlementRecord {
        providerBatchReference = visible(providerBatchReference, "Provider batch reference", 100);
        Objects.requireNonNull(settlementPeriodStart, "Settlement period start must not be null");
        Objects.requireNonNull(settlementPeriodEnd, "Settlement period end must not be null");
        if (settlementPeriodEnd.isBefore(settlementPeriodStart)) {
            throw new IllegalArgumentException("Settlement period end must not precede start");
        }
        providerRecordKey = visible(providerRecordKey, "Provider record key", 120);
        Objects.requireNonNull(operationType, "Operation type must not be null");
        providerIdempotencyKey = visible(providerIdempotencyKey, "Provider idempotency key", 120);
        if (!providerIdempotencyKey.equals(providerIdempotencyKey.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Provider idempotency key must be lowercase");
        }
        String expectedPrefix = operationType == SettlementOperationType.PAYMENT
                ? "payment:" : "reversal:";
        if (!providerIdempotencyKey.startsWith(expectedPrefix)
                || providerIdempotencyKey.length() != expectedPrefix.length() + 36) {
            throw new IllegalArgumentException("Provider idempotency key does not match operation type");
        }
        try {
            UUID parsedSubject = UUID.fromString(providerIdempotencyKey.substring(expectedPrefix.length()));
            if (!parsedSubject.toString().equals(providerIdempotencyKey.substring(expectedPrefix.length()))) {
                throw new IllegalArgumentException("Provider idempotency key must contain a canonical UUID");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Provider idempotency key must contain a canonical UUID", exception);
        }
        providerReference = visible(providerReference, "Provider reference", 120);
        Objects.requireNonNull(amount, "Amount must not be null");
        if (amount.signum() <= 0 || amount.scale() < 0) {
            throw new IllegalArgumentException("Settlement amount must be positive");
        }
        currency = requireCurrency(currency);
        int fractionDigits = Currency.getInstance(currency).getDefaultFractionDigits();
        if (amount.scale() > fractionDigits
                || amount.setScale(fractionDigits, RoundingMode.UNNECESSARY).signum() <= 0) {
            throw new IllegalArgumentException("Settlement amount exceeds currency precision");
        }
        amount = amount.setScale(fractionDigits, RoundingMode.UNNECESSARY);
        Objects.requireNonNull(transactionStatus, "Transaction status must not be null");
        Objects.requireNonNull(settlementDate, "Settlement date must not be null");
        if (settlementDate.isBefore(settlementPeriodStart)
                || settlementDate.isAfter(settlementPeriodEnd)) {
            throw new IllegalArgumentException("Settlement date must be within the declared period");
        }
        Objects.requireNonNull(providerEventTime, "Provider event time must not be null");
    }

    public String normalizedContentJson() {
        return "{" +
                field("providerBatchReference", providerBatchReference) + "," +
                field("settlementPeriodStart", DATE.format(settlementPeriodStart)) + "," +
                field("settlementPeriodEnd", DATE.format(settlementPeriodEnd)) + "," +
                field("providerRecordKey", providerRecordKey) + "," +
                field("operationType", operationType.name()) + "," +
                field("providerIdempotencyKey", providerIdempotencyKey) + "," +
                field("providerReference", providerReference) + "," +
                field("amount", amount.toPlainString()) + "," +
                field("currency", currency) + "," +
                field("transactionStatus", transactionStatus.name()) + "," +
                field("settlementDate", DATE.format(settlementDate)) + "," +
                field("providerEventTime", DateTimeFormatter.ISO_INSTANT.format(providerEventTime)) +
                "}";
    }

    public String normalizedContentHash() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalizedContentJson().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public UUID subjectId() {
        String prefix = operationType == SettlementOperationType.PAYMENT ? "payment:" : "reversal:";
        return UUID.fromString(providerIdempotencyKey.substring(prefix.length()));
    }

    private static String field(String name, String value) {
        return quote(name) + ":" + quote(value);
    }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (character < 0x20) {
                        result.append(String.format("\\u%04x", (int) character));
                    } else {
                        result.append(character);
                    }
                }
            }
        }
        return result.append('"').toString();
    }

    private static String visible(String value, String field, int maxLength) {
        Objects.requireNonNull(value, field + " must not be null");
        if (!value.matches("[\\x21-\\x7E]{1," + maxLength + "}")) {
            throw new IllegalArgumentException(field + " must contain visible ASCII characters only");
        }
        return value;
    }

    private static String requireCurrency(String value) {
        Objects.requireNonNull(value, "Currency must not be null");
        if (!value.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Currency must be an uppercase ISO 4217 code");
        }
        try {
            Currency.getInstance(value);
            return value;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unsupported currency", exception);
        }
    }
}
