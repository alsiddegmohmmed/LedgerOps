package com.ledgerops.risk.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ledgerops.risk.api.RiskConfigurationError;
import com.ledgerops.risk.api.RiskConfigurationException;
import com.ledgerops.risk.api.RiskDecision;
import com.ledgerops.risk.api.RiskEvaluationRequest;
import com.ledgerops.risk.api.RiskEvaluationResult;
import com.ledgerops.risk.api.RiskProcessingError;
import com.ledgerops.risk.api.RiskProcessingException;
import com.ledgerops.risk.domain.PaymentAmountThresholdRule;
import com.ledgerops.risk.domain.RiskEvaluation;
import com.ledgerops.risk.domain.RiskEvaluationId;
import com.ledgerops.risk.domain.RiskEvaluator;
import com.ledgerops.risk.domain.RiskProfile;
import com.ledgerops.risk.domain.RiskProfileId;
import com.ledgerops.risk.domain.RiskRuleId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

class RiskEvaluationServiceTests {

    private static final Currency SAR = Currency.getInstance("SAR");
    private static final Instant EVALUATED_AT = Instant.parse("2026-07-20T12:30:00Z");

    @Test
    void evaluatesThroughThePublishedContractAndReturnsApprovedEvidenceFields() {
        UUID tenantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        RiskProfile profile = profile(tenantId);
        StubProfileStore profileStore = new StubProfileStore(profile);
        InMemoryEvaluationStore evaluationStore = new InMemoryEvaluationStore();
        RiskEvaluationService service = service(profileStore, evaluationStore);

        RiskEvaluationResult result = service.evaluate(
                new RiskEvaluationRequest(
                        tenantId,
                        paymentId,
                        new BigDecimal("150.00"),
                        SAR
                )
        );

        assertEquals(profile.profileId().value(), result.profileId());
        assertEquals(profile.version(), result.profileVersion());
        assertEquals(25, result.uncappedScore());
        assertEquals(25, result.finalScore());
        assertEquals(RiskDecision.MANUAL_REVIEW, result.decision());
        assertEquals(evaluationStore.stored.evaluationId().value(), result.evaluationId());
        assertEquals(EVALUATED_AT, evaluationStore.stored.evaluatedAt());
        assertEquals(1, profileStore.loadCount);
    }

    @Test
    void repeatedEvaluationReturnsTheOriginalLogicalResultWithoutReevaluating() {
        UUID tenantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        RiskProfile profile = profile(tenantId);
        RiskEvaluation original = evaluation(profile, tenantId, paymentId);
        StubProfileStore profileStore = new StubProfileStore(profile);
        InMemoryEvaluationStore evaluationStore = new InMemoryEvaluationStore();
        evaluationStore.stored = original;

        RiskEvaluationResult result = service(profileStore, evaluationStore).evaluate(
                new RiskEvaluationRequest(tenantId, paymentId, BigDecimal.ONE, SAR)
        );

        assertEquals(original.evaluationId().value(), result.evaluationId());
        assertEquals(original.decision(), result.decision());
        assertEquals(0, profileStore.loadCount);
        assertEquals(0, evaluationStore.appendCount);
    }

    @Test
    void concurrentInsertWinnerBecomesTheReturnedLogicalResult() {
        UUID tenantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        RiskProfile profile = profile(tenantId);
        RiskEvaluation winner = evaluation(profile, tenantId, paymentId);
        InMemoryEvaluationStore evaluationStore = new InMemoryEvaluationStore();
        evaluationStore.concurrentWinner = winner;

        RiskEvaluationResult result = service(
                new StubProfileStore(profile),
                evaluationStore
        ).evaluate(new RiskEvaluationRequest(
                tenantId,
                paymentId,
                new BigDecimal("150.00"),
                SAR
        ));

        assertEquals(winner.evaluationId().value(), result.evaluationId());
        assertEquals(1, evaluationStore.appendCount);
    }

    @Test
    void preservesTypedConfigurationErrors() {
        RiskConfigurationException expected = new RiskConfigurationException(
                RiskConfigurationError.NO_ACTIVE_PROFILE,
                "No active Risk profile exists for the tenant"
        );
        StubProfileStore profileStore = new StubProfileStore(expected);

        RiskConfigurationException actual = assertThrows(
                RiskConfigurationException.class,
                () -> service(profileStore, new InMemoryEvaluationStore()).evaluate(
                        new RiskEvaluationRequest(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                BigDecimal.ONE,
                                SAR
                        )
                )
        );

        assertSame(expected, actual);
    }

    @Test
    void translatesUnexpectedFailuresToATypedProcessingErrorWithoutLosingTheCause() {
        IllegalStateException cause = new IllegalStateException("database unavailable");
        RiskEvaluationStore failingStore = new RiskEvaluationStore() {
            @Override
            public RiskEvaluation appendInitialOrLoadExisting(RiskEvaluation evaluation) {
                throw cause;
            }

            @Override
            public Optional<RiskEvaluation> findByTenantAndPayment(
                    UUID tenantId,
                    UUID paymentId
            ) {
                throw cause;
            }
        };

        RiskProcessingException exception = assertThrows(
                RiskProcessingException.class,
                () -> service(
                        new StubProfileStore(profile(UUID.randomUUID())),
                        failingStore
                ).evaluate(new RiskEvaluationRequest(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        BigDecimal.ONE,
                        SAR
                ))
        );

        assertEquals(RiskProcessingError.UNEXPECTED_EVALUATION_FAILURE, exception.error());
        assertSame(cause, exception.getCause());
    }

    private RiskEvaluationService service(
            RiskProfileStore profileStore,
            RiskEvaluationStore evaluationStore
    ) {
        return new RiskEvaluationService(
                profileStore,
                evaluationStore,
                new RiskEvaluator(),
                Clock.fixed(EVALUATED_AT, ZoneOffset.UTC)
        );
    }

    private RiskEvaluation evaluation(
            RiskProfile profile,
            UUID tenantId,
            UUID paymentId
    ) {
        return new RiskEvaluator().evaluate(
                RiskEvaluationId.newId(),
                tenantId,
                paymentId,
                new BigDecimal("150.00"),
                SAR,
                profile,
                EVALUATED_AT
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
                List.of(new PaymentAmountThresholdRule(
                        RiskRuleId.from(UUID.randomUUID()),
                        profileId,
                        SAR,
                        new BigDecimal("100.00"),
                        25,
                        true
                ))
        );
    }

    private static final class StubProfileStore implements RiskProfileStore {

        private final RiskProfile profile;
        private final RuntimeException failure;
        private int loadCount;

        private StubProfileStore(RiskProfile profile) {
            this.profile = profile;
            this.failure = null;
        }

        private StubProfileStore(RuntimeException failure) {
            this.profile = null;
            this.failure = failure;
        }

        @Override
        public void insert(RiskProfile profile) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RiskProfile loadActiveProfile(UUID tenantId) {
            loadCount++;
            if (failure != null) {
                throw failure;
            }
            return profile;
        }
    }

    private static final class InMemoryEvaluationStore implements RiskEvaluationStore {

        private RiskEvaluation stored;
        private RiskEvaluation concurrentWinner;
        private int appendCount;

        @Override
        public RiskEvaluation appendInitialOrLoadExisting(RiskEvaluation evaluation) {
            appendCount++;
            if (concurrentWinner != null) {
                stored = concurrentWinner;
                return concurrentWinner;
            }
            if (stored == null) {
                stored = evaluation;
            }
            return stored;
        }

        @Override
        public Optional<RiskEvaluation> findByTenantAndPayment(
                UUID tenantId,
                UUID paymentId
        ) {
            return Optional.ofNullable(stored)
                    .filter(evaluation -> evaluation.tenantId().equals(tenantId))
                    .filter(evaluation -> evaluation.paymentId().equals(paymentId));
        }
    }
}
