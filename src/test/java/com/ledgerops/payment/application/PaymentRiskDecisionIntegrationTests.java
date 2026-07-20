package com.ledgerops.payment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import com.ledgerops.risk.api.RiskProcessingException;
import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class PaymentRiskDecisionIntegrationTests {

    @Autowired
    private PaymentCreationStore creationStore;

    @Autowired
    private PaymentValidationStartService validationStartService;

    @Autowired
    private PaymentRiskDecisionService riskDecisionService;

    @Autowired
    private PaymentLifecycleStore lifecycleStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void approveCommitsEvaluationEvidenceAndApprovedPaymentAtomically() {
        Payment payment = validatingPayment("125.00");
        UUID profileId = insertProfile(payment.tenantId(), 20, 80);
        insertRule(payment.tenantId(), profileId, "SAR", "200.00", 25);

        PaymentRiskDecisionResult result = riskDecisionService.evaluate(
                payment.tenantId(),
                payment.id()
        );

        assertDecision(payment, result, PaymentStatus.APPROVED, RiskDecision.APPROVE, 0);
    }

    @Test
    void manualReviewCommitsEvaluationEvidenceAndRiskReviewPaymentAtomically() {
        Payment payment = validatingPayment("125.00");
        UUID profileId = insertProfile(payment.tenantId(), 20, 80);
        insertRule(payment.tenantId(), profileId, "SAR", "100.00", 25);

        PaymentRiskDecisionResult result = riskDecisionService.evaluate(
                payment.tenantId(),
                payment.id()
        );

        assertDecision(
                payment,
                result,
                PaymentStatus.RISK_REVIEW,
                RiskDecision.MANUAL_REVIEW,
                25
        );
    }

    @Test
    void rejectAggregatesRulesAndCommitsRejectedPaymentAtomically() {
        Payment payment = validatingPayment("125.00");
        UUID profileId = insertProfile(payment.tenantId(), 20, 80);
        insertRule(payment.tenantId(), profileId, "SAR", "100.00", 50);
        insertRule(payment.tenantId(), profileId, "SAR", "120.00", 40);

        PaymentRiskDecisionResult result = riskDecisionService.evaluate(
                payment.tenantId(),
                payment.id()
        );

        assertDecision(payment, result, PaymentStatus.REJECTED, RiskDecision.REJECT, 90);
        assertEquals(2, ruleResultCount(payment.tenantId(), payment.id()));
    }

    @Test
    void missingProfileLeavesPaymentValidatingWithoutEvaluationEvidence() {
        Payment payment = validatingPayment("125.00");

        RiskConfigurationException exception = assertThrows(
                RiskConfigurationException.class,
                () -> riskDecisionService.evaluate(payment.tenantId(), payment.id())
        );

        assertEquals(RiskConfigurationError.NO_ACTIVE_PROFILE, exception.error());
        assertValidatingWithoutEvaluation(payment);
    }

    @Test
    void repeatedDecisionDoesNotCreateAnotherEvaluationOrTransition() {
        Payment payment = validatingPayment("125.00");
        UUID profileId = insertProfile(payment.tenantId(), 20, 80);
        insertRule(payment.tenantId(), profileId, "SAR", "200.00", 25);
        riskDecisionService.evaluate(payment.tenantId(), payment.id());

        PaymentLifecycleStateException exception = assertThrows(
                PaymentLifecycleStateException.class,
                () -> riskDecisionService.evaluate(payment.tenantId(), payment.id())
        );

        assertEquals(PaymentStatus.APPROVED, exception.actualStatus());
        assertEquals(1, evaluationCount(payment.tenantId(), payment.id()));
        VersionedPayment persisted = load(payment);
        assertEquals(PaymentStatus.APPROVED, persisted.payment().status());
        assertEquals(2, persisted.version());
    }

    @Test
    void concurrentEvaluationCreatesOneEvaluationAndOnePaymentTransition() throws Exception {
        Payment payment = validatingPayment("125.00");
        UUID profileId = insertProfile(payment.tenantId(), 20, 80);
        insertRule(payment.tenantId(), profileId, "SAR", "100.00", 25);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<PaymentRiskDecisionResult> first = executor.submit(() -> {
                barrier.await();
                return riskDecisionService.evaluate(payment.tenantId(), payment.id());
            });
            Future<PaymentRiskDecisionResult> second = executor.submit(() -> {
                barrier.await();
                return riskDecisionService.evaluate(payment.tenantId(), payment.id());
            });

            int successes = 0;
            RuntimeException losingFailure = null;
            for (Future<PaymentRiskDecisionResult> future : java.util.List.of(first, second)) {
                try {
                    future.get();
                    successes++;
                } catch (ExecutionException exception) {
                    losingFailure = (RuntimeException) exception.getCause();
                }
            }

            assertEquals(1, successes);
            if (!(losingFailure instanceof PaymentLifecycleStateException)
                    && !(losingFailure instanceof PaymentOptimisticConcurrencyException)) {
                throw new AssertionError("Concurrent loser must return a typed Payment error");
            }
            VersionedPayment persisted = load(payment);
            assertEquals(PaymentStatus.RISK_REVIEW, persisted.payment().status());
            assertEquals(2, persisted.version());
            assertEquals(1, evaluationCount(payment.tenantId(), payment.id()));
            assertEquals(1, ruleResultCount(payment.tenantId(), payment.id()));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void evaluationEvidenceFailureRollsBackTheEntireSecondTransaction() {
        Payment payment = validatingPayment("125.00");
        UUID profileId = insertProfile(payment.tenantId(), 20, 80);
        insertRule(payment.tenantId(), profileId, "SAR", "100.00", 25);
        String suffix = payment.tenantId().toString().replace("-", "");
        String functionName = "risk.fail_rule_result_" + suffix;
        String triggerName = "fail_rule_result_" + suffix;
        installRuleResultFailure(functionName, triggerName, payment.tenantId());

        try {
            assertThrows(
                    RiskProcessingException.class,
                    () -> riskDecisionService.evaluate(payment.tenantId(), payment.id())
            );
        } finally {
            jdbcTemplate.execute(
                    "DROP TRIGGER " + triggerName + " ON risk.evaluated_rule_results"
            );
            jdbcTemplate.execute("DROP FUNCTION " + functionName + "()"
            );
        }

        assertValidatingWithoutEvaluation(payment);
    }

    @Test
    void paymentCompareAndSetFailureRollsBackPersistedRiskEvidence() {
        Payment payment = validatingPayment("125.00");
        UUID profileId = insertProfile(payment.tenantId(), 20, 80);
        insertRule(payment.tenantId(), profileId, "SAR", "100.00", 25);
        String suffix = payment.tenantId().toString().replace("-", "");
        String functionName = "payment.reject_cas_" + suffix;
        String triggerName = "reject_cas_" + suffix;
        installPaymentCasRejection(functionName, triggerName, payment.tenantId());

        try {
            assertThrows(
                    PaymentOptimisticConcurrencyException.class,
                    () -> riskDecisionService.evaluate(payment.tenantId(), payment.id())
            );
        } finally {
            jdbcTemplate.execute("DROP TRIGGER " + triggerName + " ON payment.payments");
            jdbcTemplate.execute("DROP FUNCTION " + functionName + "()"
            );
        }

        assertValidatingWithoutEvaluation(payment);
    }

    private void assertDecision(
            Payment payment,
            PaymentRiskDecisionResult result,
            PaymentStatus expectedStatus,
            RiskDecision expectedDecision,
            int expectedScore
    ) {
        assertEquals(expectedStatus, result.payment().payment().status());
        assertEquals(2, result.payment().version());
        assertEquals(expectedDecision, result.riskEvaluation().decision());
        assertEquals(expectedScore, result.riskEvaluation().uncappedScore());
        assertEquals(expectedScore, result.riskEvaluation().finalScore());
        VersionedPayment persisted = load(payment);
        assertEquals(expectedStatus, persisted.payment().status());
        assertEquals(2, persisted.version());
        assertEquals(1, evaluationCount(payment.tenantId(), payment.id()));
    }

    private void assertValidatingWithoutEvaluation(Payment payment) {
        VersionedPayment persisted = load(payment);
        assertEquals(PaymentStatus.VALIDATING, persisted.payment().status());
        assertEquals(1, persisted.version());
        assertEquals(0, evaluationCount(payment.tenantId(), payment.id()));
    }

    private VersionedPayment load(Payment payment) {
        return lifecycleStore.findByTenantAndId(payment.tenantId(), payment.id())
                .orElseThrow();
    }

    private int evaluationCount(UUID tenantId, PaymentId paymentId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                  FROM risk.risk_evaluations
                 WHERE tenant_id = ?
                   AND payment_id = ?
                """,
                Integer.class,
                tenantId,
                paymentId.value()
        );
    }

    private int ruleResultCount(UUID tenantId, PaymentId paymentId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                  FROM risk.evaluated_rule_results result
                  JOIN risk.risk_evaluations evaluation
                    ON evaluation.tenant_id = result.tenant_id
                   AND evaluation.id = result.evaluation_id
                 WHERE evaluation.tenant_id = ?
                   AND evaluation.payment_id = ?
                """,
                Integer.class,
                tenantId,
                paymentId.value()
        );
    }

    private Payment validatingPayment(String amount) {
        UUID tenantId = UUID.randomUUID();
        Payment payment = Payment.create(
                PaymentId.newId(),
                MerchantReference.from(tenantId, UUID.randomUUID()),
                CustomerId.from(UUID.randomUUID()),
                Money.of(new BigDecimal(amount), Currency.getInstance("SAR")),
                PaymentMethodCategory.from("card"),
                IdempotencyKey.from("risk-integration-" + UUID.randomUUID())
        );
        creationStore.insertOrFind(payment, "c".repeat(64));
        return validationStartService.start(tenantId, payment.id()).payment();
    }

    private UUID insertProfile(UUID tenantId, int reviewThreshold, int rejectThreshold) {
        UUID profileId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO risk.risk_profiles (
                    id, tenant_id, version, review_threshold, reject_threshold, active, created_at
                ) VALUES (?, ?, 1, ?, ?, true, ?)
                """,
                profileId,
                tenantId,
                reviewThreshold,
                rejectThreshold,
                Timestamp.from(Instant.parse("2026-07-20T12:00:00Z"))
        );
        return profileId;
    }

    private void insertRule(
            UUID tenantId,
            UUID profileId,
            String currency,
            String threshold,
            int contribution
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO risk.payment_amount_threshold_rules (
                    id, tenant_id, profile_id, currency, amount_threshold,
                    score_contribution, enabled
                ) VALUES (?, ?, ?, ?, ?, ?, true)
                """,
                UUID.randomUUID(),
                tenantId,
                profileId,
                currency,
                new BigDecimal(threshold),
                contribution
        );
    }

    private void installRuleResultFailure(
            String functionName,
            String triggerName,
            UUID tenantId
    ) {
        jdbcTemplate.execute(
                """
                CREATE FUNCTION %s()
                RETURNS TRIGGER
                LANGUAGE plpgsql
                AS $function$
                BEGIN
                    IF NEW.tenant_id = '%s'::uuid THEN
                        RAISE EXCEPTION 'Injected Risk evidence failure';
                    END IF;
                    RETURN NEW;
                END;
                $function$
                """.formatted(functionName, tenantId)
        );
        jdbcTemplate.execute(
                """
                CREATE TRIGGER %s
                    BEFORE INSERT ON risk.evaluated_rule_results
                    FOR EACH ROW
                    EXECUTE FUNCTION %s()
                """.formatted(triggerName, functionName)
        );
    }

    private void installPaymentCasRejection(
            String functionName,
            String triggerName,
            UUID tenantId
    ) {
        jdbcTemplate.execute(
                """
                CREATE FUNCTION %s()
                RETURNS TRIGGER
                LANGUAGE plpgsql
                AS $function$
                BEGIN
                    IF OLD.tenant_id = '%s'::uuid THEN
                        RETURN NULL;
                    END IF;
                    RETURN NEW;
                END;
                $function$
                """.formatted(functionName, tenantId)
        );
        jdbcTemplate.execute(
                """
                CREATE TRIGGER %s
                    BEFORE UPDATE ON payment.payments
                    FOR EACH ROW
                    EXECUTE FUNCTION %s()
                """.formatted(triggerName, functionName)
        );
    }
}
