package com.ledgerops.reporting.application;

import com.ledgerops.casework.api.CaseOperationalSummary;
import com.ledgerops.casework.api.CaseOperationalSummaryQuery;
import com.ledgerops.payment.api.PaymentOperationalSummaryOutcome;
import com.ledgerops.payment.api.PaymentOperationalSummaryPayment;
import com.ledgerops.payment.api.PaymentOperationalSummaryQuery;
import com.ledgerops.provider.api.ProviderHealthEvaluation;
import com.ledgerops.provider.api.ProviderOperationalSummaryQuery;
import com.ledgerops.reconciliation.api.ReconciliationDiscrepancyOperationalSummary;
import com.ledgerops.reconciliation.api.ReconciliationOperationalSummaryQuery;
import com.ledgerops.reporting.api.OperationalSummaryFact;
import com.ledgerops.reporting.api.OperationalSummaryMetricCode;
import com.ledgerops.reporting.api.OperationalSummaryProjectionRebuildPort;
import com.ledgerops.reporting.api.OperationalSummaryProjectionRebuildRequest;
import com.ledgerops.reporting.api.OperationalSummaryProjectionRebuildUseCase;
import com.ledgerops.risk.api.RiskOperationalSummaryQuery;
import com.ledgerops.risk.api.RiskReviewOperationalSummary;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Builds one complete Reporting generation from published source boundaries.
 * Source reads happen before the projection writer is invoked, so a failed
 * source read cannot leave a partially switched Reporting generation.
 */
@Service
public class OperationalSummaryProjectionRebuildService
        implements OperationalSummaryProjectionRebuildUseCase {

    private static final String SIMULATOR_PROVIDER = "SIMULATOR";
    private static final String RECONCILIATION_CASE_SOURCE = "RECONCILIATION_DISCREPANCY";

    private final PaymentOperationalSummaryQuery payments;
    private final RiskOperationalSummaryQuery risk;
    private final CaseOperationalSummaryQuery cases;
    private final ReconciliationOperationalSummaryQuery reconciliation;
    private final ProviderOperationalSummaryQuery provider;
    private final OperationalSummaryProjectionRebuildPort projection;

    public OperationalSummaryProjectionRebuildService(
            PaymentOperationalSummaryQuery payments,
            RiskOperationalSummaryQuery risk,
            CaseOperationalSummaryQuery cases,
            ReconciliationOperationalSummaryQuery reconciliation,
            ProviderOperationalSummaryQuery provider,
            OperationalSummaryProjectionRebuildPort projection
    ) {
        this.payments = payments;
        this.risk = risk;
        this.cases = cases;
        this.reconciliation = reconciliation;
        this.provider = provider;
        this.projection = projection;
    }

    /**
     * Rebuilds all approved Tenant metrics. The caller supplies the latest
     * persisted projection-event cursor represented by the read snapshot.
     */
    @Override
    public void rebuild(UUID tenantId, Instant fromInclusive, Instant toExclusive,
                        Instant asOf, long cursor) {
        if (!fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException("Summary period start must be before its exclusive end");
        }
        if (asOf == null || tenantId == null) {
            throw new NullPointerException("Tenant and projection as-of time must not be null");
        }
        List<OperationalSummaryFact> facts = new ArrayList<>();
        Set<UUID> allMerchants = Set.of();

        for (PaymentOperationalSummaryPayment payment
                : payments.findPayments(tenantId, fromInclusive, toExclusive, allMerchants)) {
            facts.add(new OperationalSummaryFact(
                    tenantId, OperationalSummaryMetricCode.PAYMENT_VOLUME, "PAYMENT",
                    payment.paymentId(), payment.merchantId(), payment.createdAt(),
                    payment.amount(), payment.currency(), null, null, null));
        }
        for (PaymentOperationalSummaryOutcome outcome : payments.findDefinitiveProviderOutcomes(
                tenantId, fromInclusive, toExclusive, allMerchants)) {
            facts.add(new OperationalSummaryFact(
                    tenantId, OperationalSummaryMetricCode.PAYMENT_PROVIDER_TERMINAL,
                    "PROVIDER_OUTCOME", outcome.paymentId(), outcome.merchantId(),
                    outcome.appliedAt(), null, null, outcome.finalCategory(), null, null));
            if (outcome.successful()) {
                facts.add(new OperationalSummaryFact(
                        tenantId, OperationalSummaryMetricCode.PAYMENT_SUCCESS,
                        "PROVIDER_OUTCOME", outcome.paymentId(), outcome.merchantId(),
                        outcome.appliedAt(), null, null, outcome.finalCategory(), null, null));
            } else if (outcome.failed()) {
                facts.add(new OperationalSummaryFact(
                        tenantId, OperationalSummaryMetricCode.PAYMENT_FAILURE,
                        "PROVIDER_OUTCOME", outcome.paymentId(), outcome.merchantId(),
                        outcome.appliedAt(), null, null, outcome.finalCategory(), null, null));
            }
        }
        for (RiskReviewOperationalSummary review
                : risk.findReviewsCreated(tenantId, fromInclusive, toExclusive, allMerchants)) {
            facts.add(new OperationalSummaryFact(
                    tenantId, OperationalSummaryMetricCode.MANUAL_REVIEW, "RISK_REVIEW",
                    review.reviewId(), review.merchantId(), review.createdAt(),
                    null, null, null, null, null));
        }
        for (CaseOperationalSummary currentCase
                : cases.findUnresolvedCasesCreated(tenantId, fromInclusive, toExclusive, allMerchants)) {
            facts.add(new OperationalSummaryFact(
                    tenantId, OperationalSummaryMetricCode.UNRESOLVED_CASE, "CASE",
                    currentCase.caseId(), currentCase.merchantId(), currentCase.createdAt(),
                    null, null, null, currentCase.currentStatus(), null));
        }
        for (ReconciliationDiscrepancyOperationalSummary discrepancy
                : reconciliation.findDiscrepancies(tenantId, fromInclusive, toExclusive, allMerchants)) {
            String currentCaseStatus = cases.findCurrentStatusBySource(
                    tenantId, RECONCILIATION_CASE_SOURCE, discrepancy.resultId()).orElse(null);
            facts.add(new OperationalSummaryFact(
                    tenantId, OperationalSummaryMetricCode.OPEN_DISCREPANCY,
                    "RECONCILIATION_RESULT", discrepancy.resultId(), discrepancy.merchantId(),
                    discrepancy.detectedAt(), null, null, null, currentCaseStatus,
                    discrepancy.currentReconciliationRun()));
        }
        addProviderHealthFacts(facts, tenantId, fromInclusive, toExclusive, asOf);

        projection.rebuild(new OperationalSummaryProjectionRebuildRequest(
                tenantId, asOf, cursor, facts));
    }

    private void addProviderHealthFacts(
            List<OperationalSummaryFact> facts,
            UUID tenantId,
            Instant fromInclusive,
            Instant toExclusive,
            Instant asOf
    ) {
        Optional<ProviderHealthEvaluation> current = provider.latestHealthAtOrBefore(
                SIMULATOR_PROVIDER, asOf);
        current.ifPresent(value -> addProviderFact(facts, tenantId, value));
        for (ProviderHealthEvaluation evaluation : provider.healthEvaluationsBetween(
                SIMULATOR_PROVIDER, fromInclusive, toExclusive)) {
            addProviderFact(facts, tenantId, evaluation);
        }
    }

    private void addProviderFact(
            List<OperationalSummaryFact> facts,
            UUID tenantId,
            ProviderHealthEvaluation evaluation
    ) {
        facts.add(new OperationalSummaryFact(
                tenantId, OperationalSummaryMetricCode.PROVIDER_HEALTH_EVALUATION,
                "PROVIDER_HEALTH", evaluation.evaluationId(), null, evaluation.evaluatedAt(),
                null, null, evaluation.state().name(), null, null));
    }
}
