package com.ledgerops.reporting.application;

import com.ledgerops.reporting.api.OperationalSummaryMetricCode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperationalSummaryCursorCodecTests {

    private static final Instant FROM = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-08-08T00:00:00Z");
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID SOURCE_ID = UUID.randomUUID();
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-04T12:00:00Z");

    @Test
    void roundTripsThePositionAndFilterIdentity() {
        String cursor = OperationalSummaryCursorCodec.encode(
                OperationalSummaryMetricCode.PAYMENT_VOLUME,
                FROM,
                TO,
                Set.of(MERCHANT_ID),
                OCCURRED_AT,
                SOURCE_ID);

        OperationalSummaryCursorCodec.Position position = OperationalSummaryCursorCodec.decode(
                cursor, OperationalSummaryMetricCode.PAYMENT_VOLUME, FROM, TO, Set.of(MERCHANT_ID));

        assertThat(position.occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(position.sourceId()).isEqualTo(SOURCE_ID);
    }

    @Test
    void rejectsReuseWithDifferentMetricOrMerchantFilter() {
        String cursor = OperationalSummaryCursorCodec.encode(
                OperationalSummaryMetricCode.PAYMENT_VOLUME,
                FROM,
                TO,
                Set.of(MERCHANT_ID),
                OCCURRED_AT,
                SOURCE_ID);

        assertThatThrownBy(() -> OperationalSummaryCursorCodec.decode(
                cursor, OperationalSummaryMetricCode.PAYMENT_FAILURE, FROM, TO, Set.of(MERCHANT_ID)))
                .isInstanceOf(InvalidOperationalSummaryCursorException.class);
        assertThatThrownBy(() -> OperationalSummaryCursorCodec.decode(
                cursor, OperationalSummaryMetricCode.PAYMENT_VOLUME, FROM, TO, Set.of(UUID.randomUUID())))
                .isInstanceOf(InvalidOperationalSummaryCursorException.class);
    }
}
