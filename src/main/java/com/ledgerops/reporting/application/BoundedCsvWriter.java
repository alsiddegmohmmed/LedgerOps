package com.ledgerops.reporting.application;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * Writes an already-authorised report shape as bounded, spreadsheet-safe CSV.
 *
 * <p>This class does not choose report columns, query source modules, or
 * perform authorization. The caller supplies those decisions.</p>
 */
@Component
public final class BoundedCsvWriter {

    private static final String CRLF = "\r\n";

    public String write(
            List<CsvExportColumn> columns,
            Iterable<? extends List<String>> rows,
            int maximumRows
    ) {
        Objects.requireNonNull(columns, "CSV columns must not be null");
        Objects.requireNonNull(rows, "CSV rows must not be null");
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("CSV must contain at least one column");
        }
        if (maximumRows <= 0) {
            throw new IllegalArgumentException("CSV maximum row count must be positive");
        }
        List<CsvExportColumn> copiedColumns = List.copyOf(columns);
        for (CsvExportColumn column : copiedColumns) {
            Objects.requireNonNull(column, "CSV column must not be null");
            if (column.containsSecretMaterial()) {
                throw new IllegalArgumentException(
                        "CSV export cannot contain secret material: " + column.header());
            }
        }

        StringBuilder csv = new StringBuilder();
        appendRow(csv, copiedColumns.stream().map(CsvExportColumn::header).toList(),
                copiedColumns.size());

        Iterator<? extends List<String>> iterator = rows.iterator();
        int rowCount = 0;
        while (iterator.hasNext()) {
            if (rowCount == maximumRows) {
                throw new IllegalArgumentException(
                        "CSV export exceeds the maximum row count: " + maximumRows);
            }
            List<String> row = iterator.next();
            Objects.requireNonNull(row, "CSV row must not be null");
            appendRow(csv, row, copiedColumns.size());
            rowCount++;
        }
        return csv.toString();
    }

    private void appendRow(StringBuilder csv, List<String> values, int expectedColumns) {
        if (values.size() != expectedColumns) {
            throw new IllegalArgumentException(
                    "CSV row has " + values.size() + " columns; expected " + expectedColumns);
        }
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                csv.append(',');
            }
            csv.append(encodeCell(values.get(index)));
        }
        csv.append(CRLF);
    }

    private String encodeCell(String value) {
        String safeValue = neutralizeFormulaPrefix(value == null ? "" : value);
        boolean quoted = safeValue.indexOf(',') >= 0
                || safeValue.indexOf('"') >= 0
                || safeValue.indexOf('\r') >= 0
                || safeValue.indexOf('\n') >= 0;
        if (!quoted) {
            return safeValue;
        }
        return '"' + safeValue.replace("\"", "\"\"") + '"';
    }

    private String neutralizeFormulaPrefix(String value) {
        int index = 0;
        while (index < value.length()
                && (value.charAt(index) == ' ' || value.charAt(index) == '\t')) {
            index++;
        }
        if (index < value.length()
                && "=+-@".indexOf(value.charAt(index)) >= 0) {
            return "'" + value;
        }
        return value;
    }
}
