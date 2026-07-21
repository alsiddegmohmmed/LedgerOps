package com.ledgerops.messaging.infrastructure;

import com.ledgerops.messaging.application.MessageEnvelopeCodec;
import com.ledgerops.messaging.application.OutboxClaim;
import com.ledgerops.messaging.application.OutboxDeliveryStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@ConditionalOnProperty(
        name = "ledgerops.messaging.publisher.enabled",
        havingValue = "true",
        matchIfMissing = true
)
class KafkaOutboxPublisher {

    static final Duration LEASE = Duration.ofSeconds(30);
    static final int MAX_ATTEMPTS = 10;
    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaOutboxPublisher.class);

    private final OutboxDeliveryStore store;
    private final MessageEnvelopeCodec codec;
    private final KafkaTemplate<String, String> kafka;
    private final Clock clock;
    private final MeterRegistry meters;
    private final String owner = "publisher-" + UUID.randomUUID();

    KafkaOutboxPublisher(
            OutboxDeliveryStore store,
            MessageEnvelopeCodec codec,
            KafkaTemplate<String, String> kafka,
            Clock clock,
            MeterRegistry meters
    ) {
        this.store = store;
        this.codec = codec;
        this.kafka = kafka;
        this.clock = clock;
        this.meters = meters;
    }

    @Scheduled(fixedDelayString = "${ledgerops.messaging.publisher.delay-ms:250}")
    void publishDue() {
        List<OutboxClaim> claims = store.claimDue(owner, clock.instant(), LEASE, 1);
        for (OutboxClaim claim : claims) {
            publish(claim);
        }
    }

    void publish(OutboxClaim claim) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Kafka publication must occur outside a database transaction");
        }
        try {
            var result = kafka.send(record(claim));
            try {
                result.get(10, TimeUnit.SECONDS);
            } catch (TimeoutException firstWaitElapsed) {
                if (!store.renew(
                        claim.outboxId(), claim.leaseToken(), clock.instant().plus(LEASE)
                )) {
                    return;
                }
                result.get(10, TimeUnit.SECONDS);
            }
            if (store.markPublished(claim.outboxId(), claim.leaseToken(), clock.instant())) {
                record("published");
            }
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            String code = exception.getClass().getSimpleName();
            if (claim.attemptCount() >= MAX_ATTEMPTS) {
                if (store.markDead(
                        claim.outboxId(), claim.leaseToken(), clock.instant(), code,
                        "Kafka publication exhausted after " + claim.attemptCount() + " attempts"
                )) {
                    record("dead");
                    LOGGER.error("Outbox publication dead outboxId={} messageId={}",
                            claim.outboxId(), claim.messageId());
                }
            } else {
                if (store.markRetryable(
                        claim.outboxId(),
                        claim.leaseToken(),
                        clock.instant().plus(retryDelay(claim)),
                        code,
                        "Kafka publication failed"
                )) {
                    record("retryable");
                }
            }
        }
    }

    Duration retryDelay(OutboxClaim claim) {
        long baseSeconds = Math.min(60, 1L << Math.min(6, claim.attemptCount() - 1));
        int bucket = Math.floorMod(claim.messageId().hashCode() + claim.attemptCount(), 41) - 20;
        long millis = baseSeconds * 1000L;
        return Duration.ofMillis(millis + (millis * bucket / 100));
    }

    private ProducerRecord<String, String> record(OutboxClaim claim) {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                claim.topic(), claim.partitionKey(), codec.encode(claim)
        );
        header(record, "messageId", claim.messageId().toString());
        header(record, "messageType", claim.messageType());
        header(record, "schemaVersion", Integer.toString(claim.schemaVersion()));
        header(record, "tenantId", claim.tenantId().toString());
        header(record, "correlationId", claim.correlationId().toString());
        header(record, "causationId", claim.causationId().toString());
        return record;
    }

    private void header(ProducerRecord<String, String> record, String name, String value) {
        record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
    }

    private void record(String outcome) {
        meters.counter("ledgerops.outbox.publish", "outcome", outcome).increment();
    }
}
