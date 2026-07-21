package com.ledgerops.messaging.infrastructure;

import com.ledgerops.messaging.application.MessageEnvelopeCodec;
import com.ledgerops.messaging.application.OutboxClaim;
import com.ledgerops.messaging.application.OutboxDeliveryStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final Tracer tracer;
    private final String owner = "publisher-" + UUID.randomUUID();

    @Autowired
    KafkaOutboxPublisher(
            OutboxDeliveryStore store,
            MessageEnvelopeCodec codec,
            KafkaTemplate<String, String> kafka,
            Clock clock,
            MeterRegistry meters,
            ObjectProvider<Tracer> tracer
    ) {
        this(store, codec, kafka, clock, meters, tracer.getIfAvailable());
    }

    KafkaOutboxPublisher(
            OutboxDeliveryStore store,
            MessageEnvelopeCodec codec,
            KafkaTemplate<String, String> kafka,
            Clock clock,
            MeterRegistry meters
    ) {
        this(store, codec, kafka, clock, meters, (Tracer) null);
    }

    private KafkaOutboxPublisher(
            OutboxDeliveryStore store,
            MessageEnvelopeCodec codec,
            KafkaTemplate<String, String> kafka,
            Clock clock,
            MeterRegistry meters,
            Tracer tracer
    ) {
        this.store = store;
        this.codec = codec;
        this.kafka = kafka;
        this.clock = clock;
        this.meters = meters;
        this.tracer = tracer;
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
        Span publishSpan = startPublishSpan(claim);
        try (Tracer.SpanInScope ignored = publishSpan == null
                ? null : tracer.withSpan(publishSpan)) {
            publishWithinTrace(claim);
        } finally {
            if (publishSpan != null) publishSpan.end();
        }
    }

    private void publishWithinTrace(OutboxClaim claim) {
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
        String traceparent = outboundTraceparent(claim.traceparent());
        if (validTraceparent(traceparent)) {
            header(record, "traceparent", traceparent);
            if (claim.tracestate() != null && validTracestate(claim.tracestate())) {
                header(record, "tracestate", claim.tracestate());
            } else if (claim.tracestate() != null) {
                recordInvalidTrace("kafka_publisher");
            }
        } else if (claim.traceparent() != null || claim.tracestate() != null) {
            recordInvalidTrace("kafka_publisher");
        }
        return record;
    }

    private Span startPublishSpan(OutboxClaim claim) {
        if (tracer == null) return null;
        Span.Builder builder = tracer.spanBuilder()
                .name("messaging.outbox.publish")
                .kind(Span.Kind.PRODUCER)
                .tag("messaging.system", "kafka")
                .tag("messaging.destination.name", claim.topic());
        if (validTraceparent(claim.traceparent())) {
            String[] parts = claim.traceparent().split("-");
            var parent = tracer.traceContextBuilder()
                    .traceId(parts[1])
                    .spanId(parts[2])
                    .sampled((Integer.parseInt(parts[3], 16) & 1) == 1)
                    .build();
            builder.setParent(parent);
        } else {
            builder.setNoParent();
        }
        return builder.start();
    }

    private boolean validTraceparent(String value) {
        return value != null && value.matches(
                "00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
    }

    private boolean validTracestate(String value) {
        return value == null || value.length() <= 512;
    }

    private String outboundTraceparent(String fallback) {
        if (tracer == null || tracer.currentSpan() == null) return fallback;
        var context = tracer.currentSpan().context();
        return "00-" + context.traceId() + "-" + context.spanId() + "-"
                + (context.sampled() ? "01" : "00");
    }

    private void recordInvalidTrace(String boundary) {
        meters.counter("ledgerops.trace.context.invalid", "boundary", boundary).increment();
    }

    private void header(ProducerRecord<String, String> record, String name, String value) {
        record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
    }

    private void record(String outcome) {
        meters.counter("ledgerops.outbox.publish", "outcome", outcome).increment();
    }
}
