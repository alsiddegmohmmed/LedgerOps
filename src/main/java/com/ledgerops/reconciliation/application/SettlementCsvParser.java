package com.ledgerops.reconciliation.application;

import com.ledgerops.reconciliation.domain.SettlementValidationReasonCode;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public final class SettlementCsvParser {

    public static final List<String> HEADER = List.of(
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
            "providerEventTime"
    );

    public void parse(InputStream input, RowHandler handler) {
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            try (var reader = new PushbackReader(new InputStreamReader(input, decoder), 1)) {
                List<String> header = readRecord(reader);
                if (header == null || !HEADER.equals(header)) {
                    throw new SettlementStructuralException(
                            SettlementValidationReasonCode.INVALID_HEADER,
                            "Settlement file header must match the approved contract exactly");
                }
                long rowNumber = 0;
                List<String> row;
                while ((row = readRecord(reader)) != null) {
                    rowNumber++;
                    if (rowNumber > 100_000) {
                        throw new SettlementStructuralException(
                                SettlementValidationReasonCode.TOO_MANY_ROWS,
                                "Settlement file exceeds the maximum row count");
                    }
                    handler.accept(rowNumber, row);
                }
                if (rowNumber == 0) {
                    throw new SettlementStructuralException(
                            SettlementValidationReasonCode.INVALID_HEADER,
                            "Settlement file must contain at least one data row");
                }
            }
        } catch (SettlementStructuralException exception) {
            throw exception;
        } catch (CharacterCodingException exception) {
            throw new SettlementStructuralException(
                    SettlementValidationReasonCode.INVALID_UTF8,
                    "Settlement file is not valid UTF-8");
        } catch (IOException exception) {
            throw new SettlementStructuralException(
                    SettlementValidationReasonCode.INVALID_FIELD,
                    "Settlement file could not be read");
        }
    }

    private List<String> readRecord(PushbackReader reader) throws IOException {
        var fields = new java.util.ArrayList<String>();
        var field = new StringBuilder();
        boolean quoted = false;
        boolean started = false;
        boolean closedQuote = false;
        boolean sawAny = false;
        int value;
        while ((value = reader.read()) != -1) {
            char character = (char) value;
            sawAny = true;
            if (character == '\0') {
                throw new SettlementStructuralException(
                        SettlementValidationReasonCode.INVALID_FIELD,
                        "Settlement file contains a NUL byte");
            }
            if (quoted) {
                if (character == '"') {
                    int next = reader.read();
                    if (next == '"') {
                        field.append('"');
                    } else {
                        quoted = false;
                        closedQuote = true;
                        if (next != -1) reader.unread(next);
                    }
                } else {
                    field.append(character);
                }
                continue;
            }
            if (closedQuote) {
                if (character == ',') {
                    fields.add(field.toString());
                    field.setLength(0);
                    started = false;
                    closedQuote = false;
                } else if (character == '\r' || character == '\n') {
                    finishField(fields, field);
                    if (character == '\r') consumeLineFeed(reader);
                    return fields;
                } else {
                    throw new SettlementStructuralException(
                            SettlementValidationReasonCode.INVALID_FIELD,
                            "CSV characters after a quoted field are not allowed");
                }
                continue;
            }
            if (character == '"') {
                if (started || field.length() > 0) {
                    throw new SettlementStructuralException(
                            SettlementValidationReasonCode.INVALID_FIELD,
                            "CSV quotes must begin a field");
                }
                quoted = true;
                started = true;
            } else if (character == ',') {
                fields.add(field.toString());
                field.setLength(0);
                started = false;
            } else if (character == '\r' || character == '\n') {
                finishField(fields, field);
                if (character == '\r') consumeLineFeed(reader);
                return fields;
            } else {
                field.append(character);
                started = true;
            }
        }
        if (quoted) {
            throw new SettlementStructuralException(
                    SettlementValidationReasonCode.INVALID_FIELD,
                    "CSV contains an unterminated quoted field");
        }
        if (!sawAny && fields.isEmpty() && field.isEmpty()) return null;
        finishField(fields, field);
        return fields;
    }

    private void finishField(java.util.List<String> fields, StringBuilder field) {
        fields.add(field.toString());
    }

    private void consumeLineFeed(PushbackReader reader) throws IOException {
        int next = reader.read();
        if (next != -1 && next != '\n') reader.unread(next);
    }

    @FunctionalInterface
    public interface RowHandler {
        void accept(long rowNumber, List<String> fields);
    }
}
