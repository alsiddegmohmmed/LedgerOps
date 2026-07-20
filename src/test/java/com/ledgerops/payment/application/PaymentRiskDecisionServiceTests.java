package com.ledgerops.payment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ledgerops.merchant.api.MerchantReference;
import com.ledgerops.payment.domain.CustomerId;
import com.ledgerops.payment.domain.IdempotencyKey;
import com.ledgerops.payment.domain.Money;
import com.ledgerops.payment.domain.Payment;
import com.ledgerops.payment.domain.PaymentId;
import com.ledgerops.payment.domain.PaymentMethodCategory;
import com.ledgerops.payment.domain.PaymentStatus;
import com.ledgerops.risk.api.RiskConfigurationError;
import com.ledgerops.risk.api.RiskConfigurationException;
import com.ledgerops.risk.api.RiskDecision;
import com.ledgerops.risk.api.RiskEvaluationRequest;
import com.ledgerops.risk.api.RiskEvaluationResult;
import com.ledgerops.risk.api.RiskEvaluationUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

class PaymentRiskDecisionServiceTests {

    @ParameterizedTest
    @MethodSource("decisionMappings")
    void mapsEachRiskDecisionToExactlyOneApprovedPaymentTransition(
            RiskDecision decision,
            PaymentStatus expectedStatus
    ) {
        Payment payment = validatingPayment();
        StubLifecycleStore lifecycleStore = new StubLifecycleStore(
                new VersionedPayment(payment, 3)
        );
        StubRiskEvaluationUseCase risk = new StubRiskEvaluationUseCase(result(decision));

        PaymentRiskDecisionResult result = new PaymentRiskDecisionService(
                lifecycleStore,
                risk
        ).evaluate(payment.tenantId(), payment.id());

        assertEquals(expectedStatus, result.payment().payment().status());
        assertEquals(4, result.payment().version());
        assertEquals(decision, result.riskEvaluation().decision());
        assertEquals(3, lifecycleStore.comparedVersion);
        assertEquals(expectedStatus, lifecycleStore.comparedPayment.status());
    }

    @Test
    void callsRiskWithOnlyTheApprovedNeutralPaymentValues() {
        Payment payment = validatingPayment();
        StubLifecycleStore lifecycleStore = new StubLifecycleStore(
                new VersionedPayment(payment, 1)
        );
        StubRiskEvaluationUseCase risk = new StubRiskEvaluationUseCase(
                result(RiskDecision.APPROVE)
        );

        new PaymentRiskDecisionService(lifecycleStore, risk).evaluate(
                payment.tenantId(),
                payment.id()
        );

        assertEquals(payment.tenantId(), risk.request.tenantId());
        assertEquals(payment.id().value(), risk.request.paymentId());
        assertEquals(payment.amount().amount(), risk.request.amount());
        assertEquals(payment.amount().currency(), risk.request.currency());
    }

    @Test
    void nonValidatingPaymentFailsBeforeRiskEvaluationOrPersistence() {
        Payment payment = createdPayment();
        StubLifecycleStore lifecycleStore = new StubLifecycleStore(
                new VersionedPayment(payment, 0)
        );
        StubRiskEvaluationUseCase risk = new StubRiskEvaluationUseCase(
                result(RiskDecision.APPROVE)
        );

        PaymentLifecycleStateException exception = assertThrows(
                PaymentLifecycleStateException.class,
                () -> new PaymentRiskDecisionService(lifecycleStore, risk).evaluate(
                        payment.tenantId(),
                        payment.id()
                )
        );

        assertEquals(PaymentStatus.VALIDATING, exception.requiredStatus());
        assertEquals(PaymentStatus.CREATED, exception.actualStatus());
        assertFalse(risk.called);
        assertFalse(lifecycleStore.compareCalled);
    }

    @Test
    void typedRiskFailureEscapesWithoutAPaymentDecisionUpdate() {
        Payment payment = validatingPayment();
        StubLifecycleStore lifecycleStore = new StubLifecycleStore(
                new VersionedPayment(payment, 1)
        );
        RiskConfigurationException expected = new RiskConfigurationException(
                RiskConfigurationError.NO_ACTIVE_PROFILE,
                "No active Risk profile exists for the tenant"
        );
        StubRiskEvaluationUseCase risk = new StubRiskEvaluationUseCase(expected);

        RiskConfigurationException actual = assertThrows(
                RiskConfigurationException.class,
                () -> new PaymentRiskDecisionService(lifecycleStore, risk).evaluate(
                        payment.tenantId(),
                        payment.id()
                )
        );

        assertSame(expected, actual);
        assertFalse(lifecycleStore.compareCalled);
    }

    @Test
    void stalePaymentVersionFailsAfterRiskAndSurfacesTypedConcurrency() {
        Payment payment = validatingPayment();
        StubLifecycleStore lifecycleStore = new StubLifecycleStore(
                new VersionedPayment(payment, 5)
        );
        lifecycleStore.compareResult = false;
        StubRiskEvaluationUseCase risk = new StubRiskEvaluationUseCase(
                result(RiskDecision.REJECT)
        );

        PaymentOptimisticConcurrencyException exception = assertThrows(
                PaymentOptimisticConcurrencyException.class,
                () -> new PaymentRiskDecisionService(lifecycleStore, risk).evaluate(
                        payment.tenantId(),
                        payment.id()
                )
        );

        assertEquals(payment.id(), exception.paymentId());
        assertEquals(5, exception.expectedVersion());
        assertTrue(risk.called);
        assertTrue(lifecycleStore.compareCalled);
    }

    private static Stream<Arguments> decisionMappings() {
        return Stream.of(
                Arguments.of(RiskDecision.APPROVE, PaymentStatus.APPROVED),
                Arguments.of(RiskDecision.MANUAL_REVIEW, PaymentStatus.RISK_REVIEW),
                Arguments.of(RiskDecision.REJECT, PaymentStatus.REJECTED)
        );
    }

    private static RiskEvaluationResult result(RiskDecision decision) {
        return new RiskEvaluationResult(
                UUID.randomUUID(),
                2,
                25,
                25,
                decision,
                UUID.randomUUID()
        );
    }

    private Payment validatingPayment() {
        return createdPayment().startValidation();
    }

    private Payment createdPayment() {
        UUID tenantId = UUID.randomUUID();
        return Payment.create(
                PaymentId.newId(),
                MerchantReference.from(tenantId, UUID.randomUUID()),
                CustomerId.from(UUID.randomUUID()),
                Money.of(new BigDecimal("125.00"), Currency.getInstance("SAR")),
                PaymentMethodCategory.from("card"),
                IdempotencyKey.from("risk-decision-" + UUID.randomUUID())
        );
    }

    private static final class StubRiskEvaluationUseCase implements RiskEvaluationUseCase {

        private final RiskEvaluationResult result;
        private final RuntimeException failure;
        private boolean called;
        private RiskEvaluationRequest request;

        private StubRiskEvaluationUseCase(RiskEvaluationResult result) {
            this.result = result;
            this.failure = null;
        }

        private StubRiskEvaluationUseCase(RuntimeException failure) {
            this.result = null;
            this.failure = failure;
        }

        @Override
        public RiskEvaluationResult evaluate(RiskEvaluationRequest request) {
            called = true;
            this.request = request;
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }

    private static final class StubLifecycleStore implements PaymentLifecycleStore {

        private final VersionedPayment stored;
        private boolean compareResult = true;
        private boolean compareCalled;
        private Payment comparedPayment;
        private long comparedVersion;

        private StubLifecycleStore(VersionedPayment stored) {
            this.stored = stored;
        }

        @Override
        public Optional<VersionedPayment> findByTenantAndId(
                UUID tenantId,
                PaymentId paymentId
        ) {
            return Optional.of(stored)
                    .filter(versioned -> versioned.payment().tenantId().equals(tenantId))
                    .filter(versioned -> versioned.payment().id().equals(paymentId));
        }

        @Override
        public boolean compareAndSet(Payment updatedPayment, long expectedVersion) {
            compareCalled = true;
            comparedPayment = updatedPayment;
            comparedVersion = expectedVersion;
            return compareResult;
        }
    }
}
