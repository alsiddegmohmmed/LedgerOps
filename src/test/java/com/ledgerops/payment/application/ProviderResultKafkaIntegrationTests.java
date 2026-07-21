package com.ledgerops.payment.application;

import com.ledgerops.ledger.domain.AccountCode;
import com.ledgerops.ledger.domain.LedgerAccount;
import com.ledgerops.ledger.domain.LedgerAccountId;
import com.ledgerops.ledger.domain.LedgerAccountRepository;
import com.ledgerops.merchant.api.MerchantReference;
import com.ledgerops.messaging.api.MessageOutbox;
import com.ledgerops.messaging.api.OutboxMessageDraft;
import com.ledgerops.messaging.api.ProducerName;
import com.ledgerops.messaging.api.StoredOutboxMessage;
import com.ledgerops.payment.domain.CustomerId;
import com.ledgerops.payment.domain.IdempotencyKey;
import com.ledgerops.payment.domain.Money;
import com.ledgerops.payment.domain.Payment;
import com.ledgerops.payment.domain.PaymentAttempt;
import com.ledgerops.payment.domain.PaymentAttemptId;
import com.ledgerops.payment.domain.PaymentId;
import com.ledgerops.payment.domain.PaymentMethodCategory;
import com.ledgerops.payment.domain.PaymentStatus;
import com.ledgerops.payment.domain.ProviderId;
import com.ledgerops.support.KafkaTestConfiguration;
import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "ledgerops.messaging.publisher.enabled=true",
        "ledgerops.payment.result-consumer.enabled=true",
        "ledgerops.provider.command-consumer.enabled=false",
        "ledgerops.provider.execution.enabled=false",
        "ledgerops.messaging.publisher.delay-ms=50"
})
@Import({PostgresTestConfiguration.class, KafkaTestConfiguration.class})
class ProviderResultKafkaIntegrationTests {

    private static final Currency SAR = Currency.getInstance("SAR");

    @Autowired
    private PaymentCreationStore creationStore;

    @Autowired
    private PaymentCompletionStore completionStore;

    @Autowired
    private PaymentSubmissionStore submissionStore;

    @Autowired
    private LedgerAccountRepository accountRepository;

    @Autowired
    private MessageOutbox outbox;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void kafkaRedeliveryAppliesOneAcceptedResultAndOneExactFinancialEffect() {
        Fixture fixture = fixture();
        Evidence evidence = evidence(fixture);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        StoredOutboxMessage observed = transaction.execute(status -> outbox.appendOrGet(
                resultDraft(fixture, evidence)
        ));

        await(() -> loadStatus(fixture.payment()) == PaymentStatus.COMPLETED,
                Duration.ofSeconds(20));
        await(() -> "PUBLISHED".equals(outboxStatus(observed.outboxId())),
                Duration.ofSeconds(10));

        jdbc.update("""
                UPDATE messaging.outbox
                   SET status = 'PENDING', next_attempt_at = CURRENT_TIMESTAMP,
                       published_at = NULL
                 WHERE id = ?
                """, observed.outboxId());
        await(() -> "PUBLISHED".equals(outboxStatus(observed.outboxId())),
                Duration.ofSeconds(20));

        assertEquals(PaymentStatus.COMPLETED, loadStatus(fixture.payment()));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM messaging.inbox
                 WHERE consumer_name = 'payment-provider-result-consumer-v1'
                   AND message_id = ? AND status = 'PROCESSED'
                """, Integer.class, observed.messageId()));
        assertEquals(1, count("payment.accepted_final_provider_results", fixture.payment()));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM messaging.outbox
                 WHERE producer_name = 'payment'
                   AND deduplication_key = ?
                """, Integer.class,
                "payment-final:" + fixture.payment().id().value()));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM ledger.transactions
                 WHERE tenant_id = ? AND source_type = 'PAYMENT' AND source_id = ?
                """, Integer.class,
                fixture.payment().tenantId(), fixture.payment().id().value()));
        assertEquals(2, jdbc.queryForObject("""
                SELECT count(*) FROM ledger.entries e
                JOIN ledger.transactions t ON t.id = e.transaction_id
                 WHERE t.tenant_id = ? AND t.source_type = 'PAYMENT' AND t.source_id = ?
                """, Integer.class,
                fixture.payment().tenantId(), fixture.payment().id().value()));
    }

    private Fixture fixture() {
        UUID tenantId = UUID.randomUUID();
        Payment payment = Payment.create(
                PaymentId.newId(),
                MerchantReference.from(tenantId, UUID.randomUUID()),
                CustomerId.from(UUID.randomUUID()),
                Money.of(new BigDecimal("88.25"), SAR),
                PaymentMethodCategory.from("card"),
                IdempotencyKey.from("kafka-result-" + UUID.randomUUID())
        );
        creationStore.insertOrFind(payment, "d".repeat(64));
        Payment validating = payment.startValidation();
        assertTrue(completionStore.compareAndSet(validating, 0));
        Payment approved = validating.approve();
        assertTrue(completionStore.compareAndSet(approved, 1));
        Payment processing = approved.startProcessing();
        assertTrue(completionStore.compareAndSet(processing, 2));
        PaymentAttempt attempt = new PaymentAttempt(
                PaymentAttemptId.from(UUID.randomUUID()),
                tenantId,
                payment.id(),
                1,
                ProviderId.SIMULATOR,
                "payment:" + payment.id().value(),
                Instant.now().truncatedTo(ChronoUnit.MICROS),
                payment.merchantReference().value(),
                payment.customerId(),
                payment.amount(),
                payment.paymentMethodCategory(),
                RequestIntentHash.calculate(payment)
        );
        submissionStore.insertAttempt(attempt);
        accountRepository.insert(LedgerAccount.create(
                LedgerAccountId.newId(), tenantId, AccountCode.PROVIDER_CLEARING,
                SAR, Instant.parse("2026-07-21T08:00:00Z")
        ));
        accountRepository.insert(LedgerAccount.create(
                LedgerAccountId.newId(), tenantId, AccountCode.MERCHANT_PAYABLE,
                SAR, Instant.parse("2026-07-21T08:00:00Z")
        ));
        return new Fixture(processing, attempt);
    }

    private Evidence evidence(Fixture fixture) {
        UUID workId = UUID.randomUUID();
        UUID interactionId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        UUID resultId = UUID.randomUUID();
        Instant observedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        jdbc.update("""
                INSERT INTO provider.work (
                    id, tenant_id, attempt_id, payment_id, work_type, status,
                    provider_id, provider_idempotency_key, request_intent_hash,
                    command_payload, due_at, correlation_id, causation_id,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'SUBMISSION', 'COMPLETED', 'SIMULATOR', ?, ?,
                          '{}', ?, ?, ?, ?, ?)
                """, workId, fixture.payment().tenantId(),
                fixture.attempt().attemptId().value(), fixture.payment().id().value(),
                fixture.attempt().providerIdempotencyKey(),
                fixture.attempt().requestIntentHash(), Timestamp.from(observedAt),
                UUID.randomUUID(), UUID.randomUUID(), Timestamp.from(observedAt),
                Timestamp.from(observedAt));
        jdbc.update("""
                INSERT INTO provider.interactions (
                    interaction_id, tenant_id, work_id, attempt_id, payment_id,
                    provider_id, work_type, request_id, request_body_hash,
                    response_body_hash, http_status, communication_outcome,
                    latency_millis, started_at, completed_at
                ) VALUES (?, ?, ?, ?, ?, 'SIMULATOR', 'SUBMISSION', ?, ?, ?, 200,
                          'RESPONSE', 1, ?, ?)
                """, interactionId, fixture.payment().tenantId(), workId,
                fixture.attempt().attemptId().value(), fixture.payment().id().value(),
                UUID.randomUUID(), "e".repeat(64), "f".repeat(64),
                Timestamp.from(observedAt.minusMillis(1)), Timestamp.from(observedAt));
        jdbc.update("""
                INSERT INTO provider.results (
                    evidence_id, tenant_id, interaction_id, work_id, attempt_id,
                    payment_id, provider_id, provider_idempotency_key,
                    provider_result_id, provider_reference, result_category,
                    retry_disposition, provider_transaction_found,
                    no_acceptance_proven, evidence_origin, observed_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'SIMULATOR', ?, ?, ?, 'SUCCESS',
                          'NOT_RETRYABLE', true, false, 'SUBMISSION_RESPONSE', ?)
                """, evidenceId, fixture.payment().tenantId(), interactionId, workId,
                fixture.attempt().attemptId().value(), fixture.payment().id().value(),
                fixture.attempt().providerIdempotencyKey(), resultId,
                "simulator-success", Timestamp.from(observedAt));
        return new Evidence(evidenceId, resultId, observedAt);
    }

    private OutboxMessageDraft resultDraft(Fixture fixture, Evidence evidence) {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put("attemptId", fixture.attempt().attemptId().value().toString());
        fields.put("evidenceOrigin", "SUBMISSION_RESPONSE");
        fields.put("observedAt", evidence.observedAt().toString());
        fields.put("paymentId", fixture.payment().id().value().toString());
        fields.put("providerEvidenceId", evidence.evidenceId().toString());
        fields.put("providerId", "SIMULATOR");
        fields.put("providerIdempotencyKey", fixture.attempt().providerIdempotencyKey());
        fields.put("providerReference", "simulator-success");
        fields.put("providerResultId", evidence.resultId().toString());
        fields.put("providerResultCategory", "SUCCESS");
        fields.put("retryDisposition", "NOT_RETRYABLE");
        return new OutboxMessageDraft(
                ProducerName.PROVIDER,
                "provider-result:" + fixture.payment().tenantId()
                        + ":SIMULATOR:" + evidence.resultId(),
                "ProviderResultObserved",
                1,
                fixture.payment().id().value(),
                fixture.payment().tenantId(),
                "ledgerops.provider.results.v1",
                fixture.payment().id().value().toString(),
                CanonicalJson.object(fields),
                UUID.randomUUID(),
                UUID.randomUUID(),
                evidence.observedAt()
        );
    }

    private PaymentStatus loadStatus(Payment payment) {
        return jdbc.queryForObject("""
                SELECT status FROM payment.payments
                 WHERE tenant_id = ? AND id = ?
                """, (rs, rowNumber) -> PaymentStatus.valueOf(rs.getString(1)),
                payment.tenantId(), payment.id().value());
    }

    private String outboxStatus(UUID outboxId) {
        return jdbc.queryForObject(
                "SELECT status FROM messaging.outbox WHERE id = ?",
                String.class,
                outboxId
        );
    }

    private int count(String table, Payment payment) {
        if (!"payment.accepted_final_provider_results".equals(table)) {
            throw new IllegalArgumentException("Test query target is not allowed");
        }
        return jdbc.queryForObject("""
                SELECT count(*) FROM payment.accepted_final_provider_results
                 WHERE tenant_id = ? AND payment_id = ?
                """, Integer.class, payment.tenantId(), payment.id().value());
    }

    private void await(BooleanSupplier condition, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while awaiting Kafka evidence", exception);
            }
        }
        assertTrue(condition.getAsBoolean(), "Timed out awaiting Kafka result application");
    }

    private record Fixture(Payment payment, PaymentAttempt attempt) {
    }

    private record Evidence(UUID evidenceId, UUID resultId, Instant observedAt) {
    }
}
