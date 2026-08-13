package com.ledgerops.reporting.application;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundedCsvWriterTests {

    private final BoundedCsvWriter writer = new BoundedCsvWriter();

    @Test
    void writesRfc4180RowsAndNeutralizesFormulaPrefixes() {
        String csv = writer.write(
                List.of(CsvExportColumn.safe("reference"), CsvExportColumn.safe("note")),
                List.of(
                        List.of("payment-1", "=HYPERLINK(\"https://example.test\")"),
                        List.of("payment,2", "line one\nline two"),
                        List.of("payment-3", "a\"quoted\" value")
                ),
                3);

        assertThat(csv).isEqualTo(
                "reference,note\r\n"
                        + "payment-1,\"'=HYPERLINK(\"\"https://example.test\"\")\"\r\n"
                        + "\"payment,2\",\"line one\nline two\"\r\n"
                        + "payment-3,\"a\"\"quoted\"\" value\"\r\n");
    }

    @Test
    void neutralizesFormulaAfterLeadingWhitespace() {
        String csv = writer.write(
                List.of(CsvExportColumn.safe("value")),
                List.of(List.of("  +SUM(A1:A2)")),
                1);

        assertThat(csv).isEqualTo("value\r\n'  +SUM(A1:A2)\r\n");
    }

    @Test
    void refusesSecretColumns() {
        assertThatThrownBy(() -> writer.write(
                List.of(CsvExportColumn.secret("clientSecret")),
                List.of(List.of("never-export")),
                1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("secret material");
    }

    @Test
    void enforcesRowBoundAndColumnShape() {
        assertThatThrownBy(() -> writer.write(
                List.of(CsvExportColumn.safe("id")),
                List.of(List.of("1"), List.of("2")),
                1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum row count");

        assertThatThrownBy(() -> writer.write(
                List.of(CsvExportColumn.safe("id")),
                List.of(List.of("1", "extra")),
                1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected 1");
    }
}
