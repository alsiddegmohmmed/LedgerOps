package com.ledgerops.payment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.ledgerops.payment.domain.ProviderId;
import com.ledgerops.support.PostgresTestConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Currency;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class PaymentSubmissionIntegrationTests {

    @Autowired
    private SubmitApprovedPayment submission;

    @Autowired
    private PaymentCreationStore creationStore;

    @Autowired
    private PaymentLifecycleStore lifecycleStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void atomicallyCreatesAttemptTransitionsAndAppendsCommand() {
        Payment payment = insertApprovedPayment();
        double createdBefore = meterRegistry.counter(
                "ledgerops.payment.submission", "outcome", "created"
        ).count();

        PaymentSubmissionResult result = submit(payment);

        assertFalse(result.replay());
        assertEquals(PaymentStatus.PROCESSING, result.payment().payment().status());
        assertEquals(ProviderId.SIMULATOR, result.attempt().providerId());
        assertEquals(1, result.attempt().sequence());
        assertEquals("payment:" + payment.id().value(), result.attempt().providerIdempotencyKey());
        assertEquals(1, attemptCount(payment));
        assertEquals(1, outboxCount(payment));
        assertEquals("SubmitPaymentToProvider", outboxValue(payment, "message_type"));
        assertEquals("ledgerops.provider.commands.v1", outboxValue(payment, "topic"));
        assertEquals(payment.id().value().toString(), outboxValue(payment, "partition_key"));
        assertEquals(createdBefore + 1, meterRegistry.counter(
                "ledgerops.payment.submission", "outcome", "created"
        ).count());
    }

    @Test
    void generatedJpyCommandUsesCanonicalZeroFractionAmount() throws Exception {
        Payment payment = insertPayment(
                PaymentStatus.APPROVED,
                new BigDecimal("125"),
                "JPY"
        );

        submit(payment);

        assertEquals(
                "125",
                tools.jackson.databind.json.JsonMapper.builder().build()
                        .readTree(outboxValue(payment, "payload"))
                        .required("amount")
                        .asText()
        );
    }

    @Test
    void equivalentReplayReturnsOriginalAttemptAndMessage() {
        Payment payment = insertApprovedPayment();
        PaymentSubmissionResult first = submit(payment);

        PaymentSubmissionResult replay = submission.submit(new SubmitApprovedPaymentCommand(
                payment.tenantId(),
                payment.id(),
                UUID.randomUUID(),
                UUID.randomUUID()
        ));

        assertTrue(replay.replay());
        assertEquals(first.attempt().attemptId(), replay.attempt().attemptId());
        assertEquals(first.messageId(), replay.messageId());
        assertEquals(1, attemptCount(payment));
        assertEquals(1, outboxCount(payment));
    }

    @Test
    void outboxInsertionFailureRollsBackAttemptAndPaymentTransition() {
        Payment payment = insertApprovedPayment();
        String suffix = payment.tenantId().toString().replace("-", "");
        String function = "messaging.reject_outbox_" + suffix;
        String trigger = "reject_outbox_" + suffix;
        jdbcTemplate.execute("CREATE FUNCTION " + function + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'forced outbox failure'; END; $$");
        jdbcTemplate.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON messaging.outbox FOR EACH ROW WHEN (NEW.tenant_id = '" + payment.tenantId() + "') EXECUTE FUNCTION " + function + "() ");

        try {
            assertThrows(PaymentSubmissionPersistenceException.class, () -> submit(payment));
        } finally {
            jdbcTemplate.execute("DROP TRIGGER " + trigger + " ON messaging.outbox");
            jdbcTemplate.execute("DROP FUNCTION " + function + "()");
        }

        assertEquals(PaymentStatus.APPROVED, load(payment).payment().status());
        assertEquals(0, attemptCount(payment));
        assertEquals(0, outboxCount(payment));
    }

    @Test
    void paymentUpdateFailureRollsBackAttemptAndCreatesNoCommand() {
        Payment payment = insertApprovedPayment();
        String suffix = payment.tenantId().toString().replace("-", "");
        String function = "payment.reject_submission_" + suffix;
        String trigger = "reject_submission_" + suffix;
        jdbcTemplate.execute("CREATE FUNCTION " + function + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'forced payment update failure'; END; $$");
        jdbcTemplate.execute("CREATE TRIGGER " + trigger + " BEFORE UPDATE ON payment.payments FOR EACH ROW WHEN (NEW.tenant_id = '" + payment.tenantId() + "') EXECUTE FUNCTION " + function + "() ");

        try {
            assertThrows(PaymentSubmissionPersistenceException.class, () -> submit(payment));
        } finally {
            jdbcTemplate.execute("DROP TRIGGER " + trigger + " ON payment.payments");
            jdbcTemplate.execute("DROP FUNCTION " + function + "()");
        }

        assertEquals(PaymentStatus.APPROVED, load(payment).payment().status());
        assertEquals(0, attemptCount(payment));
        assertEquals(0, outboxCount(payment));
    }

    @Test
    void concurrentSubmissionCreatesOneAttemptAndOneCommand() throws Exception {
        Payment payment = insertApprovedPayment();
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<PaymentSubmissionResult> first = executor.submit(() -> {
                barrier.await();
                return submit(payment);
            });
            Future<PaymentSubmissionResult> second = executor.submit(() -> {
                barrier.await();
                return submit(payment);
            });

            PaymentSubmissionResult firstResult = first.get();
            PaymentSubmissionResult secondResult = second.get();
            assertEquals(firstResult.messageId(), secondResult.messageId());
            assertEquals(1, attemptCount(payment));
            assertEquals(1, outboxCount(payment));
            assertEquals(PaymentStatus.PROCESSING, load(payment).payment().status());
        }
    }

    @Test
    void processingWithoutAttemptIsCriticalAndIsNotRepaired() {
        Payment payment = insertPayment(PaymentStatus.PROCESSING);

        assertThrows(PaymentSubmissionConsistencyException.class, () -> submit(payment));

        assertEquals(0, attemptCount(payment));
        assertEquals(0, outboxCount(payment));
    }

    @Test
    void approvedPaymentWithPartialAttemptEvidenceIsCritical() {
        Payment payment = insertApprovedPayment();
        insertAttemptOnly(payment);

        assertThrows(PaymentSubmissionConsistencyException.class, () -> submit(payment));

        assertEquals(PaymentStatus.APPROVED, load(payment).payment().status());
        assertEquals(1, attemptCount(payment));
        assertEquals(0, outboxCount(payment));
    }

    @Test
    void approvedPaymentWithOutboxOnlyEvidenceIsCritical() {
        Payment payment = insertApprovedPayment();
        insertOutboxOnly(payment, "payment-submission:" + UUID.randomUUID(), "payment");

        assertThrows(PaymentSubmissionConsistencyException.class, () -> submit(payment));

        assertEquals(PaymentStatus.APPROVED, load(payment).payment().status());
        assertEquals(0, attemptCount(payment));
        assertEquals(1, outboxCount(payment));
    }

    @Test
    void processingPaymentWithMismatchedCommandIsCriticalAndNotNormalized() {
        Payment payment = insertApprovedPayment();
        submit(payment);
        jdbcTemplate.execute(
                "ALTER TABLE messaging.outbox DISABLE TRIGGER outbox_business_content_immutable"
        );
        try {
            jdbcTemplate.update(
                    "UPDATE messaging.outbox SET payload = '{\"corrupted\":true}' WHERE tenant_id = ? AND aggregate_id = ?",
                    payment.tenantId(), payment.id().value()
            );
        } finally {
            jdbcTemplate.execute(
                    "ALTER TABLE messaging.outbox ENABLE TRIGGER outbox_business_content_immutable"
            );
        }

        assertThrows(PaymentSubmissionConsistencyException.class, () -> submit(payment));

        assertEquals(1, attemptCount(payment));
        assertEquals(1, outboxCount(payment));
        assertEquals("{\"corrupted\":true}", outboxValue(payment, "payload"));
    }

    @Test
    void processingPaymentWithMismatchedAttemptIsCriticalAndNotNormalized() {
        Payment payment = insertPayment(PaymentStatus.PROCESSING);
        UUID wrongMerchant = UUID.randomUUID();
        insertAttemptRow(payment, UUID.randomUUID(), 1, "SIMULATOR", wrongMerchant);

        assertThrows(PaymentSubmissionConsistencyException.class, () -> submit(payment));

        assertEquals(1, attemptCount(payment));
        assertEquals(0, outboxCount(payment));
        assertEquals(wrongMerchant.toString(), jdbcTemplate.queryForObject(
                "SELECT merchant_id::text FROM payment.payment_attempts WHERE tenant_id = ? AND payment_id = ?",
                String.class,
                payment.tenantId(),
                payment.id().value()
        ));
    }

    @Test
    void outboxBusinessContentCannotBeMutated() {
        Payment payment = insertApprovedPayment();
        submit(payment);

        assertThrows(Exception.class, () -> jdbcTemplate.update(
                "UPDATE messaging.outbox SET payload = '{\"corrupted\":true}' WHERE tenant_id = ? AND aggregate_id = ?",
                payment.tenantId(), payment.id().value()
        ));
    }

    @Test
    void tenantScopedLockDoesNotExposeOrChangeAnotherTenantPayment() {
        Payment payment = insertApprovedPayment();

        assertThrows(
                PaymentLifecycleNotFoundException.class,
                () -> submission.submit(new SubmitApprovedPaymentCommand(
                        UUID.randomUUID(), payment.id(), UUID.randomUUID(), UUID.randomUUID()
                ))
        );

        assertEquals(PaymentStatus.APPROVED, load(payment).payment().status());
        assertEquals(0, attemptCount(payment));
        assertEquals(0, outboxCount(payment));
    }

    @Test
    void suspendedTenantCannotStartNewProviderActivity() {
        Payment payment = insertApprovedPayment();
        jdbcTemplate.update(
                "UPDATE tenancy.tenants SET status = 'SUSPENDED', version = version + 1 WHERE id = ?",
                payment.tenantId()
        );

        assertThrows(PaymentReferenceUnavailableException.class, () -> submit(payment));

        assertEquals(PaymentStatus.APPROVED, load(payment).payment().status());
        assertEquals(0, attemptCount(payment));
        assertEquals(0, outboxCount(payment));
    }

    @Test
    void attemptsCannotBeUpdatedOrDeleted() {
        Payment payment = insertApprovedPayment();
        submit(payment);

        assertThrows(Exception.class, () -> jdbcTemplate.update(
                "UPDATE payment.payment_attempts SET sequence = 2 WHERE tenant_id = ? AND payment_id = ?",
                payment.tenantId(), payment.id().value()
        ));
        assertThrows(Exception.class, () -> jdbcTemplate.update(
                "DELETE FROM payment.payment_attempts WHERE tenant_id = ? AND payment_id = ?",
                payment.tenantId(), payment.id().value()
        ));
        assertEquals(1, attemptCount(payment));
    }

    @Test
    void databaseRejectsUnsupportedProviderValues() {
        Payment payment = insertApprovedPayment();

        assertThrows(
                Exception.class,
                () -> insertAttemptRow(payment, UUID.randomUUID(), 1, "OTHER_PROVIDER")
        );

        assertEquals(0, attemptCount(payment));
    }

    @Test
    void databaseRejectsDuplicateAttemptSequenceForOnePayment() {
        Payment payment = insertApprovedPayment();
        insertAttemptRow(payment, UUID.randomUUID(), 1, "SIMULATOR");

        assertThrows(
                Exception.class,
                () -> insertAttemptRow(payment, UUID.randomUUID(), 1, "SIMULATOR")
        );

        assertEquals(1, attemptCount(payment));
    }

    @Test
    void databaseRejectsCrossTenantAttemptReference() {
        Payment payment = insertApprovedPayment();

        assertThrows(Exception.class, () -> jdbcTemplate.update(
                """
                INSERT INTO payment.payment_attempts (
                    id, tenant_id, payment_id, sequence, provider_id,
                    provider_idempotency_key, initiated_at, merchant_id,
                    customer_id, amount, currency, payment_method_category,
                    request_intent_hash
                ) VALUES (?, ?, ?, 1, 'SIMULATOR', ?, now(), ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), UUID.randomUUID(), payment.id().value(),
                "payment:" + payment.id().value(), payment.merchantReference().value(),
                payment.customerId().value(), payment.amount().amount(),
                payment.amount().currency().getCurrencyCode(),
                payment.paymentMethodCategory().value(), RequestIntentHash.calculate(payment)
        ));
        assertEquals(0, attemptCount(payment));
    }

    @Test
    void databaseRejectsArbitraryOutboxProducer() {
        Payment payment = insertApprovedPayment();

        assertThrows(
                Exception.class,
                () -> insertOutboxOnly(payment, "invalid:" + UUID.randomUUID(), "arbitrary")
        );
        assertEquals(0, outboxCount(payment));
    }

    private PaymentSubmissionResult submit(Payment payment) {
        return submission.submit(new SubmitApprovedPaymentCommand(
                payment.tenantId(), payment.id(), UUID.randomUUID(), UUID.randomUUID()
        ));
    }

    private Payment insertApprovedPayment() {
        return insertPayment(PaymentStatus.APPROVED);
    }

    private Payment insertPayment(PaymentStatus status) {
        return insertPayment(status, new BigDecimal("125.00"), "SAR");
    }

    private Payment insertPayment(
            PaymentStatus status,
            BigDecimal amount,
            String currency
    ) {
        UUID tenantId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO tenancy.tenants (
                    id, name, default_currency, default_locale, status,
                    version, created_at, updated_at
                ) VALUES (?, ?, ?, 'en-SA', 'ACTIVE', 0, now(), now())
                """,
                tenantId,
                "Submission tenant " + tenantId,
                currency
        );
        Payment created = Payment.create(
                PaymentId.from(UUID.randomUUID()),
                MerchantReference.from(tenantId, UUID.randomUUID()),
                CustomerId.from(UUID.randomUUID()),
                Money.of(amount, Currency.getInstance(currency)),
                PaymentMethodCategory.from("CARD"),
                IdempotencyKey.from("submit-" + UUID.randomUUID())
        );
        Payment payment = Payment.rehydrate(
                created.id(), created.merchantReference(), created.customerId(), created.amount(),
                created.paymentMethodCategory(), created.idempotencyKey(), status
        );
        creationStore.insertOrFind(payment, "a".repeat(64));
        return payment;
    }

    private void insertAttemptOnly(Payment payment) {
        insertAttemptRow(payment, UUID.randomUUID(), 1, "SIMULATOR");
    }

    private void insertAttemptRow(
            Payment payment,
            UUID attemptId,
            int sequence,
            String providerId
    ) {
        insertAttemptRow(
                payment,
                attemptId,
                sequence,
                providerId,
                payment.merchantReference().value()
        );
    }

    private void insertAttemptRow(
            Payment payment,
            UUID attemptId,
            int sequence,
            String providerId,
            UUID merchantId
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO payment.payment_attempts (
                    id, tenant_id, payment_id, sequence, provider_id,
                    provider_idempotency_key, initiated_at, merchant_id,
                    customer_id, amount, currency, payment_method_category,
                    request_intent_hash
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                attemptId, payment.tenantId(), payment.id().value(),
                sequence, providerId, "payment:" + payment.id().value(),
                Timestamp.from(Instant.parse("2026-07-21T12:00:00Z")),
                merchantId, payment.customerId().value(),
                payment.amount().amount(), payment.amount().currency().getCurrencyCode(),
                payment.paymentMethodCategory().value(), RequestIntentHash.calculate(payment)
        );
    }

    private void insertOutboxOnly(
            Payment payment,
            String deduplicationKey,
            String producerName
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO messaging.outbox (
                    id, message_id, producer_name, deduplication_key, content_hash,
                    message_type, schema_version, aggregate_id, tenant_id, topic,
                    partition_key, payload, correlation_id, causation_id, occurred_at,
                    status, created_at, next_attempt_at
                ) VALUES (?, ?, ?, ?, ?, 'SubmitPaymentToProvider', 1, ?, ?,
                          'ledgerops.provider.commands.v1', ?, '{}', ?, ?, now(),
                          'PENDING', now(), now())
                """,
                UUID.randomUUID(), UUID.randomUUID(), producerName, deduplicationKey,
                "a".repeat(64), payment.id().value(), payment.tenantId(),
                payment.id().value().toString(), UUID.randomUUID(), UUID.randomUUID()
        );
    }

    private VersionedPayment load(Payment payment) {
        return lifecycleStore.findByTenantAndId(payment.tenantId(), payment.id()).orElseThrow();
    }

    private int attemptCount(Payment payment) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payment.payment_attempts WHERE tenant_id = ? AND payment_id = ?",
                Integer.class, payment.tenantId(), payment.id().value()
        );
    }

    private int outboxCount(Payment payment) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM messaging.outbox WHERE tenant_id = ? AND aggregate_id = ?",
                Integer.class, payment.tenantId(), payment.id().value()
        );
    }

    private String outboxValue(Payment payment, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM messaging.outbox WHERE tenant_id = ? AND aggregate_id = ?",
                String.class, payment.tenantId(), payment.id().value()
        );
    }
}
