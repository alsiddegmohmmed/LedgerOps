package com.ledgerops.reporting.application;

import java.util.Objects;

/** A report column declaration used by the bounded CSV writer. */
public record CsvExportColumn(
        String header,
        boolean containsSecretMaterial
) {

    public CsvExportColumn {
        Objects.requireNonNull(header, "CSV header must not be null");
        if (header.isBlank()) {
            throw new IllegalArgumentException("CSV header must not be blank");
        }
        if (header.indexOf('\r') >= 0 || header.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("CSV header must not contain line breaks");
        }
    }

    public static CsvExportColumn safe(String header) {
        return new CsvExportColumn(header, false);
    }

    public static CsvExportColumn secret(String header) {
        return new CsvExportColumn(header, true);
    }
}
