package com.ledgerops.reporting.api;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class OperationalSummaryProjectionRebuildRequestTests {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final Instant AS_OF = Instant.parse("2026-08-13T02:00:00Z");

    @Test
    void deduplicatesIdenticalFactsByMetricAndSource() {
        OperationalSummaryFact fact = paymentFact();

        OperationalSummaryProjectionRebuildRequest request =
                new OperationalSummaryProjectionRebuildRequest(
                        TENANT_ID, AS_OF, 18427, List.of(fact, fact));

        assertThat(request.facts()).containsExactly(fact);
    }

    @Test
    void rejectsConflictingFactsWithTheSameMetricAndSource() {
        OperationalSummaryFact first = paymentFact();
        OperationalSummaryFact conflicting = new OperationalSummaryFact(
                TENANT_ID, OperationalSummaryMetricCode.PAYMENT_VOLUME,
                "PAYMENT", first.sourceId(), first.merchantId(), first.occurredAt(),
                new BigDecimal("11.00"), "SAR", null, null, null);

        assertThatIllegalArgumentException().isThrownBy(() ->
                new OperationalSummaryProjectionRebuildRequest(
                        TENANT_ID, AS_OF, 18427, List.of(first, conflicting)));
    }

    @Test
    void rejectsFactsFromAnotherTenant() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new OperationalSummaryProjectionRebuildRequest(
                        TENANT_ID, AS_OF, 18427,
                        List.of(new OperationalSummaryFact(
                                UUID.randomUUID(), OperationalSummaryMetricCode.MANUAL_REVIEW,
                                "RISK_REVIEW", UUID.randomUUID(), null,
                                Instant.parse("2026-08-12T10:00:00Z"), null, null,
                                null, null, null))));
    }

    @Test
    void requiresPaymentVolumeAmountAndCurrency() {
        assertThatIllegalArgumentException().isThrownBy(() -> new OperationalSummaryFact(
                TENANT_ID, OperationalSummaryMetricCode.PAYMENT_VOLUME,
                "PAYMENT", UUID.randomUUID(), UUID.randomUUID(), AS_OF,
                null, null, null, null, null));
    }

    private static OperationalSummaryFact paymentFact() {
        return new OperationalSummaryFact(
                TENANT_ID, OperationalSummaryMetricCode.PAYMENT_VOLUME,
                "PAYMENT", UUID.randomUUID(), UUID.randomUUID(), AS_OF,
                new BigDecimal("10.00"), "sar", null, null, null);
    }
}
