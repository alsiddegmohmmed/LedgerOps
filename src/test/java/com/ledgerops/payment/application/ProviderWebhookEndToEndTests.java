package com.ledgerops.payment.application;

import com.ledgerops.ledger.domain.AccountCode;
import com.ledgerops.ledger.domain.LedgerAccount;
import com.ledgerops.ledger.domain.LedgerAccountId;
import com.ledgerops.ledger.domain.LedgerAccountRepository;
import com.ledgerops.merchant.api.MerchantReference;
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
import com.ledgerops.provider.application.ProviderWebhookExecutionStore;
import com.ledgerops.support.KafkaTestConfiguration;
import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Currency;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "ledgerops.messaging.publisher.enabled=true",
        "ledgerops.payment.result-consumer.enabled=true",
        "ledgerops.provider.command-consumer.enabled=false",
        "ledgerops.provider.execution.enabled=false",
        "ledgerops.provider.webhook.enabled=true",
        "ledgerops.provider.webhook.processing.enabled=false",
        "ledgerops.provider.webhook.key-id=simulator-to-core-v1",
        "ledgerops.provider.webhook.secret=test-only-simulator-to-core-secret",
        "ledgerops.provider.webhook.provider-client-id=ledgerops-core",
        "ledgerops.messaging.publisher.delay-ms=50"
})
@AutoConfigureMockMvc
@Import({PostgresTestConfiguration.class, KafkaTestConfiguration.class})
class ProviderWebhookEndToEndTests {
    private static final Currency SAR = Currency.getInstance("SAR");
    private static final String PATH = "/internal/provider/v1/webhooks";
    private static final String KEY_ID = "simulator-to-core-v1";
    private static final String SECRET = "test-only-simulator-to-core-secret";

    @Autowired PaymentCreationStore creationStore;
    @Autowired PaymentCompletionStore completionStore;
    @Autowired PaymentSubmissionStore submissionStore;
    @Autowired LedgerAccountRepository accountRepository;
    @Autowired ProviderWebhookExecutionStore webhookStore;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @Test
    void duplicateAndConflictingWebhooksPreserveOneAcceptedPaymentAndLedgerEffect()
            throws Exception {
        Fixture fixture = fixture();
        insertProviderMapping(fixture);
        UUID successEvent = UUID.randomUUID();
        UUID successResult = UUID.randomUUID();
        byte[] successBody = payload(fixture, successEvent, successResult, "SUCCESS");

        send(successBody, successEvent).andExpect(status().isAccepted());
        send(successBody, successEvent).andExpect(status().isAccepted());
        webhookStore.processWebhook(webhookStore.claimNextWebhook("webhook-e2e").orElseThrow());
        await(() -> loadStatus(fixture.payment()) == PaymentStatus.COMPLETED,
                Duration.ofSeconds(20));

        UUID conflictEvent = UUID.randomUUID();
        UUID conflictResult = UUID.randomUUID();
        byte[] conflictBody = payload(fixture, conflictEvent, conflictResult, "DECLINED");
        send(conflictBody, conflictEvent).andExpect(status().isAccepted());
        webhookStore.processWebhook(webhookStore.claimNextWebhook("webhook-e2e").orElseThrow());
        UUID conflictMessageId = jdbc.queryForObject("""
                SELECT message_id FROM messaging.outbox
                 WHERE producer_name = 'provider' AND deduplication_key = ?
                """, UUID.class,
                "provider-result:" + fixture.payment().tenantId()
                        + ":SIMULATOR:" + conflictResult);
        await(() -> dead(conflictMessageId), Duration.ofSeconds(20));

        assertEquals(PaymentStatus.COMPLETED, loadStatus(fixture.payment()));
        assertEquals(2, jdbc.queryForObject("""
                SELECT count(*) FROM provider.webhook_receipts
                 WHERE provider_event_id = ?
                """, Integer.class, successEvent));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM payment.accepted_final_provider_results
                 WHERE tenant_id = ? AND payment_id = ?
                """, Integer.class, fixture.payment().tenantId(),
                fixture.payment().id().value()));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM ledger.transactions
                 WHERE tenant_id = ? AND source_type = 'PAYMENT' AND source_id = ?
                """, Integer.class, fixture.payment().tenantId(),
                fixture.payment().id().value()));
        assertEquals(2, jdbc.queryForObject("""
                SELECT count(*) FROM ledger.entries e
                  JOIN ledger.transactions t ON t.id = e.transaction_id
                 WHERE t.tenant_id = ? AND t.source_type = 'PAYMENT' AND t.source_id = ?
                """, Integer.class, fixture.payment().tenantId(),
                fixture.payment().id().value()));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM messaging.outbox
                 WHERE producer_name = 'payment' AND deduplication_key = ?
                """, Integer.class, "payment-final:" + fixture.payment().id().value()));
    }

    private Fixture fixture() {
        UUID tenantId = UUID.randomUUID();
        Payment payment = Payment.create(
                PaymentId.newId(), MerchantReference.from(tenantId, UUID.randomUUID()),
                CustomerId.from(UUID.randomUUID()), Money.of(new BigDecimal("88.25"), SAR),
                PaymentMethodCategory.from("card"),
                IdempotencyKey.from("webhook-result-" + UUID.randomUUID()));
        creationStore.insertOrFind(payment, "d".repeat(64));
        Payment validating = payment.startValidation();
        assertTrue(completionStore.compareAndSet(validating, 0));
        Payment approved = validating.approve();
        assertTrue(completionStore.compareAndSet(approved, 1));
        Payment processing = approved.startProcessing();
        assertTrue(completionStore.compareAndSet(processing, 2));
        PaymentAttempt attempt = new PaymentAttempt(
                PaymentAttemptId.from(UUID.randomUUID()), tenantId, payment.id(), 1,
                ProviderId.SIMULATOR, "payment:" + payment.id().value(),
                Instant.now().truncatedTo(ChronoUnit.MICROS),
                payment.merchantReference().value(), payment.customerId(), payment.amount(),
                payment.paymentMethodCategory(), RequestIntentHash.calculate(payment));
        submissionStore.insertAttempt(attempt);
        accountRepository.insert(LedgerAccount.create(
                LedgerAccountId.newId(), tenantId, AccountCode.PROVIDER_CLEARING,
                SAR, Instant.parse("2026-07-21T08:00:00Z")));
        accountRepository.insert(LedgerAccount.create(
                LedgerAccountId.newId(), tenantId, AccountCode.MERCHANT_PAYABLE,
                SAR, Instant.parse("2026-07-21T08:00:00Z")));
        return new Fixture(processing, attempt);
    }

    private void insertProviderMapping(Fixture fixture) {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO provider.work
                    (id, tenant_id, attempt_id, payment_id, attempt_sequence, work_type,
                     status, provider_id, provider_idempotency_key, request_intent_hash,
                     command_payload, due_at, correlation_id, causation_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, 1, 'SUBMISSION', 'COMPLETED', 'SIMULATOR', ?, ?, '{}',
                        ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), fixture.payment().tenantId(),
                fixture.attempt().attemptId().value(), fixture.payment().id().value(),
                fixture.attempt().providerIdempotencyKey(),
                fixture.attempt().requestIntentHash(), Timestamp.from(now), UUID.randomUUID(),
                UUID.randomUUID(), Timestamp.from(now), Timestamp.from(now));
    }

    private byte[] payload(
            Fixture fixture, UUID eventId, UUID resultId, String category) {
        return ("{" +
                "\"providerEventId\":\"" + eventId + "\"," +
                "\"providerResultId\":\"" + resultId + "\"," +
                "\"providerIdempotencyKey\":\""
                + fixture.attempt().providerIdempotencyKey() + "\"," +
                "\"providerReference\":\"simulator-transaction-"
                + fixture.payment().id().value() + "\"," +
                "\"providerResultCategory\":\"" + category + "\"," +
                "\"providerOccurredAt\":\"2026-07-21T12:00:00Z\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private org.springframework.test.web.servlet.ResultActions send(byte[] body, UUID eventId)
            throws Exception {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        return mvc.perform(post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("X-LedgerOps-Key-Id", KEY_ID)
                .header("X-LedgerOps-Timestamp", timestamp)
                .header("X-LedgerOps-Event-Id", eventId)
                .header("X-LedgerOps-Signature", sign(timestamp, eventId, body)));
    }

    private String sign(String timestamp, UUID eventId, byte[] body) {
        try {
            String hash = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(body));
            String canonical = "v1\nPOST\n" + PATH + "\n" + KEY_ID + "\n"
                    + timestamp + "\n" + eventId + "\n" + hash;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            return "v1=" + Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private PaymentStatus loadStatus(Payment payment) {
        return jdbc.queryForObject("""
                SELECT status FROM payment.payments WHERE tenant_id = ? AND id = ?
                """, (rs, rowNumber) -> PaymentStatus.valueOf(rs.getString(1)),
                payment.tenantId(), payment.id().value());
    }

    private boolean dead(UUID messageId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM messaging.inbox
                 WHERE consumer_name = 'payment-provider-result-consumer-v1'
                   AND message_id = ? AND status = 'DEAD'
                """, Integer.class, messageId);
        return count != null && count == 1;
    }

    private void await(BooleanSupplier condition, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) return;
            try {
                Thread.sleep(50);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while awaiting webhook evidence", exception);
            }
        }
        assertTrue(condition.getAsBoolean(), "Timed out awaiting webhook result application");
    }

    private record Fixture(Payment payment, PaymentAttempt attempt) {
    }
}
