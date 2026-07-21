package com.ledgerops.provider.infrastructure;

import com.ledgerops.messaging.api.MessageOutbox;
import com.ledgerops.messaging.api.OutboxMessageDraft;
import com.ledgerops.messaging.api.ProducerName;
import com.ledgerops.messaging.api.StoredOutboxMessage;
import com.ledgerops.support.KafkaTestConfiguration;
import com.ledgerops.support.PostgresTestConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.SdkTracerProviderBuilderCustomizer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "ledgerops.messaging.publisher.enabled=true",
        "ledgerops.provider.command-consumer.enabled=true",
        "ledgerops.messaging.publisher.delay-ms=50"
})
@Import({PostgresTestConfiguration.class, KafkaTestConfiguration.class,
        ProviderCommandDeliveryIntegrationTests.TraceExportConfiguration.class})
class ProviderCommandDeliveryIntegrationTests {

    private static final String TRACEPARENT =
            "00-00000000000000000000000000000001-0000000000000002-01";
    private static final String TRACESTATE = "ledgerops=release-0.2";

    @Autowired
    private MessageOutbox outbox;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private KafkaContainer kafkaContainer;

    @Autowired
    private InMemorySpanExporter spanExporter;

    @Autowired
    private MeterRegistry meters;

    @Test
    void committedCommandReachesOneInboxAndOneDurableProviderWorkAcrossRedelivery() {
        UUID tenantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        OutboxMessageDraft draft = draft(tenantId, paymentId, attemptId);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        StoredOutboxMessage stored = transaction.execute(status -> outbox.appendOrGet(draft));
        await(() -> count("provider.work", "tenant_id", tenantId) == 1, Duration.ofSeconds(20));
        await(() -> "PUBLISHED".equals(jdbc.queryForObject(
                "SELECT status FROM messaging.outbox WHERE id = ?", String.class,
                stored.outboxId()
        )), Duration.ofSeconds(10));

        // Simulate the documented crash window: Kafka accepted the message, but the
        // publisher did not retain its PUBLISHED update and publishes it again.
        jdbc.update("""
                UPDATE messaging.outbox
                   SET status = 'PENDING', next_attempt_at = CURRENT_TIMESTAMP,
                       published_at = NULL
                 WHERE id = ?
                """, stored.outboxId());
        await(() -> "PUBLISHED".equals(jdbc.queryForObject(
                "SELECT status FROM messaging.outbox WHERE id = ?", String.class,
                stored.outboxId()
        )), Duration.ofSeconds(20));

        assertEquals(1, count("provider.work", "tenant_id", tenantId));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM messaging.inbox
                 WHERE consumer_name = 'provider-command-consumer-v1'
                   AND message_id = ? AND status = 'PROCESSED'
                """, Integer.class, stored.messageId()));
        assertEquals("SUBMISSION", jdbc.queryForObject(
                "SELECT work_type FROM provider.work WHERE tenant_id = ?", String.class, tenantId
        ));
        assertEquals("SIMULATOR", jdbc.queryForObject(
                "SELECT provider_id FROM provider.work WHERE tenant_id = ?", String.class, tenantId
        ));
        String deliveredTraceparent = jdbc.queryForObject(
                "SELECT traceparent FROM provider.work WHERE tenant_id = ?",
                String.class, tenantId);
        assertTrue(deliveredTraceparent.startsWith(
                "00-00000000000000000000000000000001-"));
        assertEquals(TRACESTATE, jdbc.queryForObject(
                "SELECT tracestate FROM provider.work WHERE tenant_id = ?",
                String.class, tenantId));

        await(() -> spanExporter.getFinishedSpanItems().stream().anyMatch(span ->
                        span.getName().equals("messaging.outbox.publish")
                                && span.getTraceId().equals(
                                "00000000000000000000000000000001")),
                Duration.ofSeconds(20));
        var publishSpan = spanExporter.getFinishedSpanItems().stream()
                .filter(span -> span.getName().equals("messaging.outbox.publish"))
                .filter(span -> span.getTraceId().equals(
                        "00000000000000000000000000000001"))
                .findFirst().orElseThrow();
        assertEquals("0000000000000002", publishSpan.getParentSpanId());
        assertEquals(SpanKind.PRODUCER, publishSpan.getKind());
        var consumerSpan = spanExporter.getFinishedSpanItems().stream()
                .filter(span ->
                span.getTraceId().equals(publishSpan.getTraceId())
                        && span.getKind() == SpanKind.CONSUMER)
                .findFirst().orElseThrow();
        assertTrue(spanExporter.getFinishedSpanItems().stream()
                .filter(span -> span.getName().equals("messaging.outbox.publish"))
                .anyMatch(span -> span.getSpanId().equals(consumerSpan.getParentSpanId())));
    }

    @Test
    void invalidOptionalTraceHeaderIsDroppedWithoutPoisoningBusinessWork() throws Exception {
        UUID messageId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        String raw = envelope(messageId, tenantId, paymentId, 1,
                payload(paymentId, attemptId));
        var properties = new java.util.Properties();
        properties.put(org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafkaContainer.getBootstrapServers());
        properties.put(org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                org.apache.kafka.common.serialization.StringSerializer.class);
        properties.put(org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                org.apache.kafka.common.serialization.StringSerializer.class);
        try (var producer = new org.apache.kafka.clients.producer.KafkaProducer<String, String>(
                properties)) {
            var record = new org.apache.kafka.clients.producer.ProducerRecord<>(
                    "ledgerops.provider.commands.v1", paymentId.toString(), raw
            );
            record.headers().add("traceparent", "invalid".getBytes(
                    java.nio.charset.StandardCharsets.UTF_8));
            producer.send(record).get(10, java.util.concurrent.TimeUnit.SECONDS);
        }

        await(() -> count("provider.work", "tenant_id", tenantId) == 1,
                Duration.ofSeconds(20));

        assertEquals("PROCESSED", jdbc.queryForObject("""
                SELECT status FROM messaging.inbox
                 WHERE consumer_name = 'provider-command-consumer-v1' AND message_id = ?
                """, String.class, messageId));
        String replacementTrace = jdbc.queryForObject(
                "SELECT traceparent FROM provider.work WHERE tenant_id = ?",
                String.class, tenantId);
        assertTrue(replacementTrace.matches(
                "00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}"));
        assertTrue(meters.get("ledgerops.trace.context.invalid")
                .tag("boundary", "provider_command_consumer").counter().count() >= 1);
    }

    @Test
    void kafkaOutageLeavesCommittedOutboxWorkRecoverableAfterBrokerResumes() {
        UUID tenantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        kafkaContainer.getDockerClient().pauseContainerCmd(kafkaContainer.getContainerId()).exec();
        StoredOutboxMessage stored;
        try {
            stored = transaction.execute(status -> outbox.appendOrGet(
                    draft(tenantId, paymentId, attemptId)
            ));
            await(() -> "RETRYABLE".equals(jdbc.queryForObject(
                    "SELECT status FROM messaging.outbox WHERE id = ?",
                    String.class, stored.outboxId()
            )), Duration.ofSeconds(35));
        } finally {
            kafkaContainer.getDockerClient().unpauseContainerCmd(
                    kafkaContainer.getContainerId()
            ).exec();
        }

        await(() -> "PUBLISHED".equals(jdbc.queryForObject(
                "SELECT status FROM messaging.outbox WHERE id = ?",
                String.class, stored.outboxId()
        )), Duration.ofSeconds(30));
        await(() -> count("provider.work", "tenant_id", tenantId) == 1,
                Duration.ofSeconds(20));
    }

    @Test
    void kafkaTransportInvalidAndUnsupportedVersionRecordsReachTheirExactDeadLetters()
            throws Exception {
        long suffix = Math.abs(UUID.randomUUID().getMostSignificantBits());
        String invalidKey = "transport-" + suffix;
        String invalidRecord = "not-json-" + suffix;
        kafkaTemplate.send("ledgerops.provider.commands.v1", invalidKey, invalidRecord)
                .get(10, java.util.concurrent.TimeUnit.SECONDS);
        await(() -> jdbc.queryForObject("""
                SELECT count(*) FROM messaging.transport_dead_letters
                 WHERE consumer_name = 'provider-command-consumer-v1'
                   AND raw_record_hash = ?
                """, Integer.class, sha256(invalidRecord)) == 1, Duration.ofSeconds(20));

        UUID messageId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        String unsupported = envelope(messageId, tenantId, paymentId, 2, "{}");
        kafkaTemplate.send(
                "ledgerops.provider.commands.v1", paymentId.toString(), unsupported
        ).get(10, java.util.concurrent.TimeUnit.SECONDS);
        await(() -> jdbc.queryForObject("""
                SELECT count(*) FROM messaging.inbox
                 WHERE consumer_name = 'provider-command-consumer-v1' AND message_id = ?
                   AND status = 'DEAD'
                """, Integer.class, messageId) == 1, Duration.ofSeconds(20));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM messaging.consumer_dead_letters
                 WHERE consumer_name = 'provider-command-consumer-v1' AND message_id = ?
                   AND reason_code = 'UNSUPPORTED_SCHEMA_VERSION'
                """, Integer.class, messageId));
    }

    @Test
    void kafkaPoisonPayloadCountsFiveFailuresThenCommitsDeadInboxAndDeadLetter()
            throws Exception {
        UUID messageId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        String poison = envelope(messageId, tenantId, paymentId, 1, "{}");

        kafkaTemplate.send("ledgerops.provider.commands.v1", paymentId.toString(), poison)
                .get(10, java.util.concurrent.TimeUnit.SECONDS);

        await(() -> jdbc.queryForObject("""
                SELECT count(*) FROM messaging.consumer_failures
                 WHERE consumer_name = 'provider-command-consumer-v1' AND message_id = ?
                   AND failure_count = 5
                """, Integer.class, messageId) == 1, Duration.ofSeconds(25));
        assertEquals("DEAD", jdbc.queryForObject("""
                SELECT status FROM messaging.inbox
                 WHERE consumer_name = 'provider-command-consumer-v1' AND message_id = ?
                """, String.class, messageId));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM messaging.consumer_dead_letters
                 WHERE consumer_name = 'provider-command-consumer-v1' AND message_id = ?
                """, Integer.class, messageId));
    }

    private OutboxMessageDraft draft(UUID tenantId, UUID paymentId, UUID attemptId) {
        String payload = payload(paymentId, attemptId);
        return new OutboxMessageDraft(
                ProducerName.PAYMENT,
                "payment-submission:" + attemptId,
                "SubmitPaymentToProvider",
                1,
                paymentId,
                tenantId,
                "ledgerops.provider.commands.v1",
                paymentId.toString(),
                payload,
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.now(),
                TRACEPARENT,
                TRACESTATE
        );
    }

    private String payload(UUID paymentId, UUID attemptId) {
        return "{" +
                "\"attemptId\":\"" + attemptId + "\"," +
                "\"paymentId\":\"" + paymentId + "\"," +
                "\"attemptSequence\":1," +
                "\"providerId\":\"SIMULATOR\"," +
                "\"providerIdempotencyKey\":\"payment:" + paymentId + "\"," +
                "\"amount\":\"12.30\"," +
                "\"currency\":\"SAR\"," +
                "\"paymentMethodCategory\":\"CARD\"," +
                "\"requestIntentHash\":\"" + "a".repeat(64) + "\"}";
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TraceExportConfiguration {
        @Bean
        InMemorySpanExporter inMemorySpanExporter() {
            return InMemorySpanExporter.create();
        }

        @Bean
        SdkTracerProviderBuilderCustomizer traceExporterCustomizer(
                InMemorySpanExporter exporter) {
            return builder -> builder.addSpanProcessor(SimpleSpanProcessor.create(exporter));
        }
    }

    private String envelope(
            UUID messageId,
            UUID tenantId,
            UUID paymentId,
            int schemaVersion,
            String payload
    ) {
        return "{" +
                "\"messageId\":\"" + messageId + "\"," +
                "\"messageType\":\"SubmitPaymentToProvider\"," +
                "\"schemaVersion\":" + schemaVersion + "," +
                "\"aggregateId\":\"" + paymentId + "\"," +
                "\"tenantId\":\"" + tenantId + "\"," +
                "\"correlationId\":\"" + UUID.randomUUID() + "\"," +
                "\"causationId\":\"" + UUID.randomUUID() + "\"," +
                "\"occurredAt\":\"2026-07-21T12:00:00Z\"," +
                "\"payload\":" + payload + "}";
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            );
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private int count(String table, String column, UUID value) {
        if (!table.equals("provider.work") || !column.equals("tenant_id")) {
            throw new IllegalArgumentException("Test query target is not allowed");
        }
        return jdbc.queryForObject(
                "SELECT count(*) FROM provider.work WHERE tenant_id = ?", Integer.class, value
        );
    }

    private void await(java.util.function.BooleanSupplier condition, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while awaiting asynchronous evidence", exception);
            }
        }
        assertTrue(condition.getAsBoolean(), "Timed out awaiting asynchronous evidence");
    }
}
