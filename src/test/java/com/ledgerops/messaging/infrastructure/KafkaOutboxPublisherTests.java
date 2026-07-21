package com.ledgerops.messaging.infrastructure;

import com.ledgerops.messaging.application.MessageEnvelopeCodec;
import com.ledgerops.messaging.application.OutboxClaim;
import com.ledgerops.messaging.application.OutboxDeliveryStore;
import org.junit.jupiter.api.Test;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

class KafkaOutboxPublisherTests {

    @Test
    void retryJitterIsDeterministicAndBounded() {
        KafkaOutboxPublisher publisher = publisher();
        OutboxClaim claim = claim(4);

        long first = publisher.retryDelay(claim).toMillis();
        long repeated = publisher.retryDelay(claim).toMillis();

        assertEquals(first, repeated);
        org.junit.jupiter.api.Assertions.assertTrue(first >= 6_400 && first <= 9_600);
    }

    @Test
    void kafkaPublicationIsRejectedInsideADatabaseTransaction() {
        KafkaOutboxPublisher publisher = publisher();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(IllegalStateException.class, () -> publisher.publish(claim(1)));
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void kafkaOutageLeavesTheCommittedOutboxRecordRetryable() {
        OutboxDeliveryStore store = mock(OutboxDeliveryStore.class);
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(any(ProducerRecord.class))).thenReturn(
                CompletableFuture.failedFuture(new IllegalStateException("Kafka unavailable"))
        );
        KafkaOutboxPublisher publisher = publisher(store, kafka);
        OutboxClaim claim = claim(1);

        publisher.publish(claim);

        verify(store).markRetryable(
                eq(claim.outboxId()), eq(claim.leaseToken()), any(Instant.class),
                eq("ExecutionException"), eq("Kafka publication failed")
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishedRecordCarriesStableIdentityHeadersAndPaymentPartitionKey() {
        OutboxDeliveryStore store = mock(OutboxDeliveryStore.class);
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(any(ProducerRecord.class))).thenReturn(
                CompletableFuture.completedFuture(null)
        );
        KafkaOutboxPublisher publisher = publisher(store, kafka);
        OutboxClaim claim = claim(1);

        publisher.publish(claim);

        var captor = org.mockito.ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(captor.capture());
        ProducerRecord<?, ?> record = captor.getValue();
        assertEquals(claim.partitionKey(), record.key());
        assertEquals(claim.messageId().toString(), header(record, "messageId"));
        assertEquals(claim.correlationId().toString(), header(record, "correlationId"));
        verify(store).markPublished(
                eq(claim.outboxId()), eq(claim.leaseToken()), any(Instant.class)
        );
    }

    @SuppressWarnings("unchecked")
    private KafkaOutboxPublisher publisher() {
        return publisher(mock(OutboxDeliveryStore.class), mock(KafkaTemplate.class));
    }

    private KafkaOutboxPublisher publisher(
            OutboxDeliveryStore store,
            KafkaTemplate<String, String> kafka
    ) {
        return new KafkaOutboxPublisher(
                store,
                new MessageEnvelopeCodec(),
                kafka,
                Clock.fixed(Instant.parse("2026-07-21T12:00:00Z"), ZoneOffset.UTC),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry()
        );
    }

    private String header(ProducerRecord<?, ?> record, String name) {
        return new String(record.headers().lastHeader(name).value(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private OutboxClaim claim(int attempt) {
        UUID messageId = UUID.fromString("00000000-0000-0000-0000-000000000011");
        return new OutboxClaim(
                UUID.randomUUID(), messageId, "SubmitPaymentToProvider", 1,
                UUID.randomUUID(), UUID.randomUUID(), "topic", "key", "{}",
                UUID.randomUUID(), UUID.randomUUID(), Instant.now(), attempt, UUID.randomUUID()
        );
    }
}
