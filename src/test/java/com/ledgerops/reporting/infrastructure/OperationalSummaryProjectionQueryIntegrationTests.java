package com.ledgerops.reporting.infrastructure;

import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.identity.domain.Permission;
import com.ledgerops.identity.domain.PrincipalType;
import com.ledgerops.identity.domain.ScopeMode;
import com.ledgerops.reporting.api.OperationalSummaryFact;
import com.ledgerops.reporting.api.OperationalSummaryMetricCode;
import com.ledgerops.reporting.api.OperationalSummaryProjectionRebuildRequest;
import com.ledgerops.reporting.api.OperationalSummaryRecordsRequest;
import com.ledgerops.reporting.api.OperationalSummaryRequest;
import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class OperationalSummaryProjectionQueryIntegrationTests {

    private static final Instant FROM = Instant.parse("2026-08-13T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-08-14T00:00:00Z");

    @Autowired
    private OperationalSummaryProjectionStore store;

    @Test
    void aggregateAndDrillDownUseTheSameProjectionFacts() {
        UUID tenantId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        UUID paymentOne = UUID.randomUUID();
        UUID paymentTwo = UUID.randomUUID();
        UUID healthBeforePeriod = UUID.randomUUID();
        UUID healthInPeriod = UUID.randomUUID();

        List<OperationalSummaryFact> facts = new ArrayList<>(List.of(
                fact(tenantId, OperationalSummaryMetricCode.PAYMENT_VOLUME, "PAYMENT",
                        paymentOne, merchantId, "2026-08-13T01:00:00Z", "100.00", "SAR"),
                fact(tenantId, OperationalSummaryMetricCode.PAYMENT_VOLUME, "PAYMENT",
                        paymentTwo, merchantId, "2026-08-13T02:00:00Z", "50.00", "USD"),
                outcome(tenantId, OperationalSummaryMetricCode.PAYMENT_SUCCESS, paymentOne,
                        merchantId, "2026-08-13T03:00:00Z", "SUCCESS"),
                outcome(tenantId, OperationalSummaryMetricCode.PAYMENT_FAILURE, paymentTwo,
                        merchantId, "2026-08-13T04:00:00Z", "DECLINED"),
                outcome(tenantId, OperationalSummaryMetricCode.PAYMENT_PROVIDER_TERMINAL,
                        paymentOne, merchantId, "2026-08-13T03:00:00Z", "SUCCESS"),
                outcome(tenantId, OperationalSummaryMetricCode.PAYMENT_PROVIDER_TERMINAL,
                        paymentTwo, merchantId, "2026-08-13T04:00:00Z", "DECLINED"),
                fact(tenantId, OperationalSummaryMetricCode.MANUAL_REVIEW, "RISK_REVIEW",
                        UUID.randomUUID(), merchantId, "2026-08-13T05:00:00Z", null, null),
                discrepancy(tenantId, UUID.randomUUID(), merchantId,
                        "2026-08-13T06:00:00Z", true, null),
                discrepancy(tenantId, UUID.randomUUID(), merchantId,
                        "2026-08-13T07:00:00Z", true, "CLOSED"),
                discrepancy(tenantId, UUID.randomUUID(), merchantId,
                        "2026-08-13T08:00:00Z", false, null),
                fact(tenantId, OperationalSummaryMetricCode.UNRESOLVED_CASE, "CASE",
                        UUID.randomUUID(), merchantId, "2026-08-13T09:00:00Z", null, null,
                        "OPEN"),
                health(tenantId, healthBeforePeriod, "2026-08-12T23:00:00Z", "HEALTHY"),
                health(tenantId, healthInPeriod, "2026-08-13T10:00:00Z", "DEGRADED")
        ));
        store.rebuild(new OperationalSummaryProjectionRebuildRequest(
                tenantId, TO, 18427, facts));

        OperationalSummaryRequest request = new OperationalSummaryRequest(
                tenantId, FROM, TO, Set.of(), authorization(tenantId));
        var summary = store.findSummary(request);

        assertThat(summary.metrics().paymentVolume().paymentCount()).isEqualTo(2);
        assertThat(summary.metrics().paymentVolume().amountByCurrency())
                .extracting(value -> value.currency())
                .containsExactly("SAR", "USD");
        assertThat(summary.metrics().paymentVolume().amountByCurrency().get(0).amount())
                .isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(summary.metrics().paymentVolume().amountByCurrency().get(1).amount())
                .isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(summary.metrics().paymentSuccessRate().numerator()).isEqualTo(1);
        assertThat(summary.metrics().paymentSuccessRate().denominator()).isEqualTo(2);
        assertThat(summary.metrics().paymentSuccessRate().rate())
                .isEqualByComparingTo(new BigDecimal("0.5"));
        assertThat(summary.metrics().paymentFailureRate().rate())
                .isEqualByComparingTo(new BigDecimal("0.5"));
        assertThat(summary.metrics().manualReviewCount().count()).isEqualTo(1);
        assertThat(summary.metrics().openDiscrepancyCount().count()).isEqualTo(1);
        assertThat(summary.metrics().unresolvedCaseCount().count()).isEqualTo(1);
        assertThat(summary.metrics().providerHealth().currentState().name()).isEqualTo("DEGRADED");
        assertThat(summary.metrics().providerHealth().worstState().name()).isEqualTo("DEGRADED");

        var replay = store.replayAfter(tenantId, summary.projection().cursor() - 1, Set.of(merchantId));
        assertThat(replay.resyncRequired()).isFalse();
        assertThat(replay.events()).singleElement().satisfies(event -> {
            assertThat(event.eventId()).isEqualTo(summary.projection().cursor());
            assertThat(event.generation()).isEqualTo(summary.projection().generation());
            assertThat(event.affected()).containsExactly(
                    com.ledgerops.reporting.api.ReportingProjectionAffected.OPERATIONAL_SUMMARY);
        });
        assertThat(store.replayAfter(tenantId, summary.projection().cursor(), Set.of()).events())
                .isEmpty();

        for (OperationalSummaryMetricCode metric : List.of(
                OperationalSummaryMetricCode.PAYMENT_VOLUME,
                OperationalSummaryMetricCode.PAYMENT_SUCCESS,
                OperationalSummaryMetricCode.PAYMENT_FAILURE,
                OperationalSummaryMetricCode.PAYMENT_PROVIDER_TERMINAL,
                OperationalSummaryMetricCode.MANUAL_REVIEW,
                OperationalSummaryMetricCode.OPEN_DISCREPANCY,
                OperationalSummaryMetricCode.UNRESOLVED_CASE)) {
            var page = store.findRecords(new OperationalSummaryRecordsRequest(
                    tenantId, metric, FROM, TO, Set.of(), null, 100, authorization(tenantId)));
            long aggregate = switch (metric) {
                case PAYMENT_VOLUME -> summary.metrics().paymentVolume().paymentCount();
                case PAYMENT_SUCCESS -> summary.metrics().paymentSuccessRate().numerator();
                case PAYMENT_FAILURE -> summary.metrics().paymentFailureRate().numerator();
                case PAYMENT_PROVIDER_TERMINAL -> summary.metrics().paymentSuccessRate().denominator();
                case MANUAL_REVIEW -> summary.metrics().manualReviewCount().count();
                case OPEN_DISCREPANCY -> summary.metrics().openDiscrepancyCount().count();
                case UNRESOLVED_CASE -> summary.metrics().unresolvedCaseCount().count();
                case PROVIDER_HEALTH_EVALUATION -> throw new AssertionError();
            };
            assertThat(page.items()).as(metric.name()).hasSize((int) aggregate);
        }
    }

    private static OperationalSummaryFact fact(
            UUID tenantId, OperationalSummaryMetricCode metric, String sourceType,
            UUID sourceId, UUID merchantId, String occurredAt, String amount, String currency) {
        return fact(tenantId, metric, sourceType, sourceId, merchantId, occurredAt,
                amount, currency, null);
    }

    private static OperationalSummaryFact fact(
            UUID tenantId, OperationalSummaryMetricCode metric, String sourceType,
            UUID sourceId, UUID merchantId, String occurredAt, String amount, String currency,
            String currentState) {
        return new OperationalSummaryFact(
                tenantId, metric, sourceType, sourceId, merchantId,
                Instant.parse(occurredAt), amount == null ? null : new BigDecimal(amount),
                currency, null, currentState, null);
    }

    private static OperationalSummaryFact outcome(
            UUID tenantId, OperationalSummaryMetricCode metric, UUID sourceId,
            UUID merchantId, String occurredAt, String valueCode) {
        return new OperationalSummaryFact(tenantId, metric, "PROVIDER_OUTCOME", sourceId,
                merchantId, Instant.parse(occurredAt), null, null, valueCode, null, null);
    }

    private static OperationalSummaryFact discrepancy(
            UUID tenantId, UUID sourceId, UUID merchantId, String occurredAt,
            boolean currentRun, String currentState) {
        return new OperationalSummaryFact(tenantId, OperationalSummaryMetricCode.OPEN_DISCREPANCY,
                "RECONCILIATION_RESULT", sourceId, merchantId, Instant.parse(occurredAt),
                null, null, null, currentState, currentRun);
    }

    private static OperationalSummaryFact health(
            UUID tenantId, UUID sourceId, String occurredAt, String state) {
        return new OperationalSummaryFact(tenantId,
                OperationalSummaryMetricCode.PROVIDER_HEALTH_EVALUATION, "PROVIDER_HEALTH",
                sourceId, null, Instant.parse(occurredAt), null, null, state, null, null);
    }

    private static AuthorizedRequestContext authorization(UUID tenantId) {
        return new AuthorizedRequestContext(
                PrincipalType.HUMAN, UUID.randomUUID(), null, tenantId, ScopeMode.TENANT_WIDE,
                Set.of(), Set.of(Permission.REPORT_READ), UUID.randomUUID().toString());
    }
}
