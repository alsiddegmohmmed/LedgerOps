package com.ledgerops.reporting.application;

import com.ledgerops.casework.api.CaseOperationalSummary;
import com.ledgerops.casework.api.CaseOperationalSummaryQuery;
import com.ledgerops.payment.api.PaymentOperationalSummaryOutcome;
import com.ledgerops.payment.api.PaymentOperationalSummaryPayment;
import com.ledgerops.payment.api.PaymentOperationalSummaryQuery;
import com.ledgerops.provider.api.ProviderHealthEvaluation;
import com.ledgerops.provider.api.ProviderHealthState;
import com.ledgerops.provider.api.ProviderOperationalSummaryQuery;
import com.ledgerops.reconciliation.api.ReconciliationDiscrepancyOperationalSummary;
import com.ledgerops.reconciliation.api.ReconciliationOperationalSummaryQuery;
import com.ledgerops.reporting.api.OperationalSummaryFact;
import com.ledgerops.reporting.api.OperationalSummaryMetricCode;
import com.ledgerops.reporting.api.OperationalSummaryProjectionRebuildPort;
import com.ledgerops.reporting.api.OperationalSummaryProjectionRebuildRequest;
import com.ledgerops.risk.api.RiskOperationalSummaryQuery;
import com.ledgerops.risk.api.RiskReviewOperationalSummary;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperationalSummaryProjectionRebuildServiceTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final Instant FROM = Instant.parse("2026-08-13T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-08-14T00:00:00Z");

    @Test
    void composesAllApprovedSourceFactsBeforeSwitchingProjection() {
        PaymentOperationalSummaryQuery payments = mock(PaymentOperationalSummaryQuery.class);
        RiskOperationalSummaryQuery risk = mock(RiskOperationalSummaryQuery.class);
        CaseOperationalSummaryQuery cases = mock(CaseOperationalSummaryQuery.class);
        ReconciliationOperationalSummaryQuery reconciliation =
                mock(ReconciliationOperationalSummaryQuery.class);
        ProviderOperationalSummaryQuery provider = mock(ProviderOperationalSummaryQuery.class);
        OperationalSummaryProjectionRebuildPort projection =
                mock(OperationalSummaryProjectionRebuildPort.class);

        UUID paymentId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        UUID resultId = UUID.randomUUID();
        Instant eventAt = Instant.parse("2026-08-13T12:00:00Z");

        when(payments.findPayments(TENANT, FROM, TO, Set.of())).thenReturn(List.of(
                new PaymentOperationalSummaryPayment(paymentId, TENANT, merchantId,
                        new BigDecimal("12.50"), "SAR", eventAt)));
        when(payments.findDefinitiveProviderOutcomes(TENANT, FROM, TO, Set.of())).thenReturn(List.of(
                new PaymentOperationalSummaryOutcome(paymentId, TENANT, merchantId,
                        "SUCCESS", eventAt)));
        when(risk.findReviewsCreated(TENANT, FROM, TO, Set.of())).thenReturn(List.of(
                new RiskReviewOperationalSummary(reviewId, TENANT, paymentId, merchantId, eventAt)));
        when(cases.findUnresolvedCasesCreated(TENANT, FROM, TO, Set.of())).thenReturn(List.of(
                new CaseOperationalSummary(caseId, TENANT, "RISK_REVIEW", reviewId, paymentId,
                        merchantId, "OPEN", eventAt)));
        when(cases.findCurrentStatusBySource(TENANT, "RECONCILIATION_DISCREPANCY", resultId))
                .thenReturn(Optional.of("CLOSED"));
        when(reconciliation.findDiscrepancies(TENANT, FROM, TO, Set.of())).thenReturn(List.of(
                new ReconciliationDiscrepancyOperationalSummary(resultId, TENANT, "PAYMENT",
                        paymentId, merchantId, eventAt, true)));
        ProviderHealthEvaluation health = new ProviderHealthEvaluation(
                UUID.randomUUID(), "SIMULATOR", UUID.randomUUID(), 1, 1,
                ProviderHealthState.HEALTHY, 10, 10, 0, 0, 12,
                "CLOSED", FROM.minusSeconds(60), eventAt, eventAt);
        when(provider.latestHealthAtOrBefore("SIMULATOR", TO)).thenReturn(Optional.of(health));
        when(provider.healthEvaluationsBetween("SIMULATOR", FROM, TO)).thenReturn(List.of(health));

        OperationalSummaryProjectionRebuildService service = new OperationalSummaryProjectionRebuildService(
                payments, risk, cases, reconciliation, provider, projection);
        service.rebuild(TENANT, FROM, TO, TO, 18427);

        var request = org.mockito.ArgumentCaptor.forClass(OperationalSummaryProjectionRebuildRequest.class);
        verify(projection).rebuild(request.capture());
        List<OperationalSummaryFact> facts = request.getValue().facts();

        assertEquals(7, facts.size());
        assertTrue(facts.stream().anyMatch(f -> f.metric() == OperationalSummaryMetricCode.PAYMENT_VOLUME));
        assertTrue(facts.stream().anyMatch(f -> f.metric() == OperationalSummaryMetricCode.PAYMENT_SUCCESS));
        assertTrue(facts.stream().anyMatch(f -> f.metric() == OperationalSummaryMetricCode.PAYMENT_PROVIDER_TERMINAL));
        assertTrue(facts.stream().anyMatch(f -> f.metric() == OperationalSummaryMetricCode.MANUAL_REVIEW));
        assertTrue(facts.stream().anyMatch(f -> f.metric() == OperationalSummaryMetricCode.UNRESOLVED_CASE));
        OperationalSummaryFact discrepancy = facts.stream()
                .filter(f -> f.metric() == OperationalSummaryMetricCode.OPEN_DISCREPANCY)
                .findFirst().orElseThrow();
        assertEquals("CLOSED", discrepancy.currentState());
        assertEquals(true, discrepancy.currentReconciliationRun());
        assertEquals(1, facts.stream()
                .filter(f -> f.metric() == OperationalSummaryMetricCode.PROVIDER_HEALTH_EVALUATION)
                .count());
    }
}
