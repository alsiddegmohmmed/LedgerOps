package com.ledgerops.provider.infrastructure;

import com.ledgerops.messaging.api.ConsumerFailureResult;
import com.ledgerops.messaging.api.ConsumerMessageStore;
import com.ledgerops.messaging.api.IncomingMessage;
import com.ledgerops.provider.application.ProviderSubmissionCommand;
import com.ledgerops.provider.application.AcceptProviderSubmissionCommand;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;

@Component
@ConditionalOnProperty(
        name = "ledgerops.provider.command-consumer.enabled",
        havingValue = "true",
        matchIfMissing = true
)
class ProviderCommandConsumer {

    static final String CONSUMER_NAME = "provider-command-consumer-v1";
    private final ProviderCommandEnvelopeParser parser = new ProviderCommandEnvelopeParser();
    private final ConsumerMessageStore messages;
    private final AcceptProviderSubmissionCommand acceptance;
    private final MeterRegistry meters;
    private final Tracer tracer;

    @Autowired
    ProviderCommandConsumer(
            ConsumerMessageStore messages,
            AcceptProviderSubmissionCommand acceptance,
            MeterRegistry meters,
            ObjectProvider<Tracer> tracer
    ) {
        this(messages, acceptance, meters, tracer.getIfAvailable());
    }

    ProviderCommandConsumer(
            ConsumerMessageStore messages,
            AcceptProviderSubmissionCommand acceptance,
            MeterRegistry meters
    ) {
        this(messages, acceptance, meters, (Tracer) null);
    }

    private ProviderCommandConsumer(
            ConsumerMessageStore messages,
            AcceptProviderSubmissionCommand acceptance,
            MeterRegistry meters,
            Tracer tracer
    ) {
        this.messages = messages;
        this.acceptance = acceptance;
        this.meters = meters;
        this.tracer = tracer;
    }

    @KafkaListener(
            topics = "ledgerops.provider.commands.v1",
            groupId = CONSUMER_NAME,
            containerFactory = "providerCommandKafkaListenerContainerFactory",
            properties = "spring.json.value.default.type=java.lang.String"
    )
    void receive(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        Span span = startConsumerSpan(record);
        try (Tracer.SpanInScope ignored = span == null ? null : tracer.withSpan(span)) {
            receiveWithinTrace(record, acknowledgment);
        } finally {
            if (span != null) span.end();
        }
    }

    private void receiveWithinTrace(
            ConsumerRecord<String, String> record,
            Acknowledgment acknowledgment
    ) {
        String raw = record.value() == null ? "" : record.value();
        UUID messageId = parser.trustworthyMessageId(raw);
        if (messageId == null) {
            messages.recordTransportDeadLetter(
                    CONSUMER_NAME, record.topic(), record.partition(), record.offset(),
                    sha256(raw), null,
                    "INVALID_ENVELOPE", "No trustworthy messageId", null
            );
            recordDead("transport_invalid");
            acknowledgment.acknowledge();
            return;
        }

        ProviderCommandEnvelope envelope;
        try {
            envelope = parser.parse(raw);
        } catch (PermanentlyInvalidMessageException exception) {
            IncomingMessage malformed = new IncomingMessage(
                    CONSUMER_NAME, messageId, null, "INVALID_ENVELOPE"
            );
            ConsumerFailureResult result = messages.recordFailure(
                    malformed, raw, sha256(raw), record.topic(), record.partition(),
                    record.offset(), "PERMANENTLY_INVALID_ENVELOPE",
                    "Envelope is structurally invalid", null, true
            );
            if (result.dead()) {
                recordDead("invalid_envelope");
                acknowledgment.acknowledge();
            }
            return;
        }
        IncomingMessage incoming = new IncomingMessage(
                CONSUMER_NAME, envelope.messageId(), envelope.tenantId(), envelope.messageType()
        );
        if (envelope.schemaVersion() != 1) {
            recordFailure(record, raw, envelope, incoming, "UNSUPPORTED_SCHEMA_VERSION",
                    true, acknowledgment);
            return;
        }
        if (!"SubmitPaymentToProvider".equals(envelope.messageType())) {
            recordFailure(record, raw, envelope, incoming, "UNSUPPORTED_MESSAGE_TYPE",
                    true, acknowledgment);
            return;
        }
        if (!envelope.aggregateId().toString().equals(record.key())) {
            recordFailure(record, raw, envelope, incoming, "PARTITION_KEY_MISMATCH",
                    true, acknowledgment);
            return;
        }
        try {
            ProviderSubmissionCommand command = command(envelope, record);
            var result = acceptance.accept(incoming, command);
            if (result == com.ledgerops.messaging.api.InboxResult.DUPLICATE) {
                meters.counter(
                        "ledgerops.inbox.duplicate", "consumer", CONSUMER_NAME
                ).increment();
            }
            acknowledgment.acknowledge();
        } catch (RuntimeException exception) {
            recordFailure(record, raw, envelope, incoming, "BUSINESS_PROCESSING_FAILURE",
                    false, acknowledgment);
        }
    }

    private void recordFailure(
            ConsumerRecord<String, String> record,
            String raw,
            ProviderCommandEnvelope envelope,
            IncomingMessage incoming,
            String code,
            boolean immediatelyDead,
            Acknowledgment acknowledgment
    ) {
        ConsumerFailureResult result = messages.recordFailure(
                incoming, raw, sha256(envelope.canonicalPayload()), record.topic(),
                record.partition(), record.offset(), code, code,
                envelope.correlationId(), immediatelyDead
        );
        if (result.dead()) {
            recordDead(code.toLowerCase(java.util.Locale.ROOT));
            acknowledgment.acknowledge();
        } else {
            acknowledgment.nack(Duration.ofSeconds(1));
        }
    }

    private ProviderSubmissionCommand command(
            ProviderCommandEnvelope envelope,
            ConsumerRecord<String, String> record
    ) {
        JsonNode payload = envelope.payload();
        UUID attemptId = uuid(payload, "attemptId");
        UUID paymentId = uuid(payload, "paymentId");
        JsonNode sequence = payload.get("attemptSequence");
        if (sequence == null || !sequence.isIntegralNumber() || sequence.intValue() < 1) {
            throw new PermanentlyInvalidMessageException("Attempt sequence is invalid");
        }
        if (!paymentId.equals(envelope.aggregateId())) {
            throw new PermanentlyInvalidMessageException("Aggregate ID must equal Payment ID");
        }
        String providerId = text(payload, "providerId");
        if (!"SIMULATOR".equals(providerId)) {
            throw new PermanentlyInvalidMessageException("Release 0.2 provider must be SIMULATOR");
        }
        String key = text(payload, "providerIdempotencyKey");
        if (!key.equals("payment:" + paymentId.toString().toLowerCase())) {
            throw new PermanentlyInvalidMessageException("Provider idempotency key is invalid");
        }
        String hash = text(payload, "requestIntentHash");
        if (!hash.matches("[0-9a-f]{64}")) {
            throw new PermanentlyInvalidMessageException("Request intent hash is invalid");
        }
        String amount = text(payload, "amount");
        if (!amount.matches("(0|[1-9][0-9]*)(\\.[0-9]+)?")) {
            throw new PermanentlyInvalidMessageException("Amount is invalid");
        }
        if (!text(payload, "currency").matches("[A-Z]{3}")) {
            throw new PermanentlyInvalidMessageException("Currency is invalid");
        }
        text(payload, "paymentMethodCategory");
        String traceparent = header(record, "traceparent");
        String tracestate = header(record, "tracestate");
        if (traceparent != null && !traceparent.matches(
                "00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")) {
            recordInvalidTrace();
            traceparent = null;
            tracestate = null;
        }
        if (tracestate != null && tracestate.length() > 512) {
            recordInvalidTrace();
            tracestate = null;
        }
        traceparent = currentTraceparent(traceparent);
        return new ProviderSubmissionCommand(
                envelope.tenantId(), envelope.messageId(), attemptId, paymentId,
                sequence.intValue(),
                providerId, key, hash, envelope.canonicalPayload(),
                envelope.correlationId(), envelope.causationId(), traceparent, tracestate
        );
    }

    private String header(ConsumerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private UUID uuid(JsonNode payload, String field) {
        return UUID.fromString(text(payload, field));
    }

    private String text(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw new PermanentlyInvalidMessageException(field + " must be a string");
        }
        return value.asString();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void recordDead(String reason) {
        meters.counter(
                "ledgerops.consumer.dead",
                "consumer", CONSUMER_NAME,
                "reason", reason
        ).increment();
    }

    private void recordInvalidTrace() {
        meters.counter(
                "ledgerops.trace.context.invalid", "boundary", "provider_command_consumer"
        ).increment();
    }

    private Span startConsumerSpan(ConsumerRecord<String, String> record) {
        if (tracer == null) return null;
        Span.Builder builder = tracer.spanBuilder()
                .name("provider.command.consume")
                .kind(Span.Kind.CONSUMER)
                .tag("messaging.system", "kafka")
                .tag("messaging.destination.name", "ledgerops.provider.commands.v1");
        String value = header(record, "traceparent");
        if (value != null && value.matches(
                "00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")) {
            String[] parts = value.split("-");
            builder.setParent(tracer.traceContextBuilder()
                    .traceId(parts[1])
                    .spanId(parts[2])
                    .sampled((Integer.parseInt(parts[3], 16) & 1) == 1)
                    .build());
        } else {
            builder.setNoParent();
        }
        return builder.start();
    }

    private String currentTraceparent(String fallback) {
        if (tracer == null || tracer.currentSpan() == null) return fallback;
        var context = tracer.currentSpan().context();
        return "00-" + context.traceId() + "-" + context.spanId() + "-"
                + (context.sampled() ? "01" : "00");
    }
}
