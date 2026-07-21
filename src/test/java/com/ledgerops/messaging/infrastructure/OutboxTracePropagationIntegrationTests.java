package com.ledgerops.messaging.infrastructure;

import com.ledgerops.messaging.api.MessageOutbox;
import com.ledgerops.messaging.api.OutboxMessageDraft;
import com.ledgerops.messaging.api.ProducerName;
import com.ledgerops.support.PostgresTestConfiguration;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(properties = {
        "ledgerops.messaging.publisher.enabled=false",
        "ledgerops.observability.kafka-lag.enabled=false",
        "ledgerops.provider.command-consumer.enabled=false",
        "ledgerops.provider.execution.enabled=false",
        "ledgerops.payment.result-consumer.enabled=false",
        "ledgerops.provider.webhook.enabled=false"
})
@Import(PostgresTestConfiguration.class)
class OutboxTracePropagationIntegrationTests {

    @Autowired MessageOutbox outbox;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired Tracer tracer;

    @Test
    void activeTraceContextIsPersistedForDelayedKafkaPublication() {
        UUID paymentId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        var span = tracer.nextSpan().name("outbox-origin").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                    outbox.appendOrGet(new OutboxMessageDraft(
                            ProducerName.PAYMENT, "payment-submission:" + paymentId,
                            "SubmitPaymentToProvider", 1, paymentId, tenantId,
                            "ledgerops.provider.commands.v1", paymentId.toString(), "{}",
                            UUID.randomUUID(), UUID.randomUUID(), Instant.now())));
        } finally {
            span.end();
        }

        String traceparent = jdbc.queryForObject("""
                SELECT traceparent FROM messaging.outbox
                 WHERE producer_name = 'payment' AND deduplication_key = ?
                """, String.class, "payment-submission:" + paymentId);
        assertNotNull(traceparent);
        assertEquals("00-" + span.context().traceId() + "-" + span.context().spanId() + "-"
                        + (span.context().sampled() ? "01" : "00"), traceparent);
    }
}
