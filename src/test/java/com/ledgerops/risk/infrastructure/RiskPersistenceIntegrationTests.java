package com.ledgerops.risk.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ledgerops.risk.application.RiskEvaluationStore;
import com.ledgerops.risk.application.RiskProfileStore;
import com.ledgerops.risk.api.RiskConfigurationError;
import com.ledgerops.risk.api.RiskConfigurationException;
import com.ledgerops.risk.api.RiskDecision;
import com.ledgerops.risk.domain.EvaluatedRiskRule;
import com.ledgerops.risk.domain.PaymentAmountThresholdRule;
import com.ledgerops.risk.domain.RiskEvaluation;
import com.ledgerops.risk.domain.RiskEvaluationId;
import com.ledgerops.risk.domain.RiskEvaluator;
import com.ledgerops.risk.domain.RiskProfile;
import com.ledgerops.risk.domain.RiskProfileId;
import com.ledgerops.risk.domain.RiskRuleId;
import com.ledgerops.risk.domain.RiskRuleType;
import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class RiskPersistenceIntegrationTests {

    private static final Currency SAR = Currency.getInstance("SAR");

    @Autowired
    private RiskProfileStore profileStore;

    @Autowired
    private RiskEvaluationStore evaluationStore;

    @Test
    void insertsAndLoadsTheExactActiveProfileVersionAndRules() {
        UUID tenantId = UUID.randomUUID();
        RiskProfile profile = profile(tenantId);

        profileStore.insert(profile);

        RiskProfile loaded = profileStore.loadActiveProfile(tenantId);

        assertEquals(profile.profileId(), loaded.profileId());
        assertEquals(profile.tenantId(), loaded.tenantId());
        assertEquals(profile.version(), loaded.version());
        assertEquals(profile.reviewThreshold(), loaded.reviewThreshold());
        assertEquals(profile.rejectThreshold(), loaded.rejectThreshold());
        assertEquals(profile.active(), loaded.active());
        assertEquals(profile.createdAt(), loaded.createdAt());
        assertEquals(Set.copyOf(profile.rules()), Set.copyOf(loaded.rules()));
    }

    @Test
    void surfacesMissingActiveProfileAsTypedConfigurationError() {
        RiskConfigurationException exception = assertThrows(
                RiskConfigurationException.class,
                () -> profileStore.loadActiveProfile(UUID.randomUUID())
        );

        assertEquals(RiskConfigurationError.NO_ACTIVE_PROFILE, exception.error());
    }

    @Test
    void appendsAndReloadsOneCompleteEvaluationWithRuleEvidence() {
        UUID tenantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        RiskProfile profile = profile(tenantId);
        profileStore.insert(profile);
        RiskEvaluation evaluation = new RiskEvaluator().evaluate(
                RiskEvaluationId.from(UUID.randomUUID()),
                tenantId,
                paymentId,
                new BigDecimal("150.00"),
                SAR,
                profile,
                Instant.parse("2026-07-20T12:30:00Z")
        );

        evaluationStore.appendInitialOrLoadExisting(evaluation);

        RiskEvaluation loaded = evaluationStore.findByTenantAndPayment(
                tenantId,
                paymentId
        ).orElseThrow();

        assertEquals(evaluation.evaluationId(), loaded.evaluationId());
        assertEquals(evaluation.tenantId(), loaded.tenantId());
        assertEquals(evaluation.paymentId(), loaded.paymentId());
        assertEquals(evaluation.profileId(), loaded.profileId());
        assertEquals(evaluation.profileVersion(), loaded.profileVersion());
        assertEquals(evaluation.uncappedScore(), loaded.uncappedScore());
        assertEquals(evaluation.finalScore(), loaded.finalScore());
        assertEquals(evaluation.decision(), loaded.decision());
        assertEquals(evaluation.evaluatedAt(), loaded.evaluatedAt());
        assertEquals(Set.copyOf(evaluation.ruleResults()), Set.copyOf(loaded.ruleResults()));
        assertFalse(
                evaluationStore.findByTenantAndPayment(UUID.randomUUID(), paymentId).isPresent()
        );
    }

    @Test
    void sequentialRepeatReturnsTheOriginalPersistedEvaluation() {
        UUID tenantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        RiskProfile profile = profile(tenantId);
        profileStore.insert(profile);
        RiskEvaluation first = evaluate(profile, tenantId, paymentId, UUID.randomUUID());
        RiskEvaluation second = evaluate(profile, tenantId, paymentId, UUID.randomUUID());

        evaluationStore.appendInitialOrLoadExisting(first);

        RiskEvaluation repeated = evaluationStore.appendInitialOrLoadExisting(second);
        assertEquals(first.evaluationId(), repeated.evaluationId());
        assertEquals(
                first.evaluationId(),
                evaluationStore.findByTenantAndPayment(tenantId, paymentId)
                        .orElseThrow()
                        .evaluationId()
        );
    }

    @Test
    void coordinatedConcurrentAppendsReturnOnePersistedEvaluation() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        RiskProfile profile = profile(tenantId);
        profileStore.insert(profile);
        int competitors = 8;
        CyclicBarrier barrier = new CyclicBarrier(competitors);
        ExecutorService executor = Executors.newFixedThreadPool(competitors);

        try {
            List<Future<RiskEvaluation>> futures = new ArrayList<>();
            for (int index = 0; index < competitors; index++) {
                futures.add(executor.submit(() -> {
                    RiskEvaluation candidate = evaluate(
                            profile,
                            tenantId,
                            paymentId,
                            UUID.randomUUID()
                    );
                    barrier.await();
                    return evaluationStore.appendInitialOrLoadExisting(candidate);
                }));
            }

            Set<RiskEvaluationId> evaluationIds = new java.util.HashSet<>();
            for (Future<RiskEvaluation> future : futures) {
                evaluationIds.add(future.get().evaluationId());
            }

            assertEquals(1, evaluationIds.size());
            assertEquals(
                    evaluationIds.iterator().next(),
                    evaluationStore.findByTenantAndPayment(tenantId, paymentId)
                            .orElseThrow()
                            .evaluationId()
            );
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void failedRuleEvidenceInsertRollsBackTheParentEvaluation() {
        UUID tenantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        RiskProfile profile = profile(tenantId);
        profileStore.insert(profile);
        RiskEvaluation invalid = new RiskEvaluation(
                RiskEvaluationId.from(UUID.randomUUID()),
                tenantId,
                paymentId,
                profile.profileId(),
                profile.version(),
                25,
                25,
                RiskDecision.MANUAL_REVIEW,
                Instant.parse("2026-07-20T12:30:00Z"),
                List.of(new EvaluatedRiskRule(
                        RiskRuleId.from(UUID.randomUUID()),
                        RiskRuleType.PAYMENT_AMOUNT_THRESHOLD,
                        SAR,
                        new BigDecimal("100.00"),
                        25,
                        true,
                        25
                ))
        );

        assertThrows(
                DataIntegrityViolationException.class,
                () -> evaluationStore.appendInitialOrLoadExisting(invalid)
        );
        assertFalse(evaluationStore.findByTenantAndPayment(tenantId, paymentId).isPresent());
    }

    private RiskEvaluation evaluate(
            RiskProfile profile,
            UUID tenantId,
            UUID paymentId,
            UUID evaluationId
    ) {
        return new RiskEvaluator().evaluate(
                RiskEvaluationId.from(evaluationId),
                tenantId,
                paymentId,
                new BigDecimal("150.00"),
                SAR,
                profile,
                Instant.parse("2026-07-20T12:30:00Z")
        );
    }

    private RiskProfile profile(UUID tenantId) {
        RiskProfileId profileId = RiskProfileId.from(UUID.randomUUID());
        return new RiskProfile(
                profileId,
                tenantId,
                3,
                20,
                80,
                true,
                Instant.parse("2026-07-20T12:00:00Z"),
                List.of(
                        new PaymentAmountThresholdRule(
                                RiskRuleId.from(UUID.randomUUID()),
                                profileId,
                                SAR,
                                new BigDecimal("100.00"),
                                25,
                                true
                        ),
                        new PaymentAmountThresholdRule(
                                RiskRuleId.from(UUID.randomUUID()),
                                profileId,
                                SAR,
                                new BigDecimal("200.00"),
                                30,
                                true
                        )
                )
        );
    }
}
