package com.ledgerops.payment.infrastructure;

import com.ledgerops.messaging.api.ConsumerFailureResult;
import com.ledgerops.messaging.api.ConsumerMessageStore;
import com.ledgerops.messaging.api.IncomingMessage;
import com.ledgerops.payment.application.ApplyProviderResult;
import com.ledgerops.payment.application.PaymentProviderResultCommand;
import com.ledgerops.payment.application.PaymentProviderResultConsistencyException;
import com.ledgerops.provider.api.ProviderResultCategory;
import com.ledgerops.provider.api.RetryDisposition;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

@Component
@ConditionalOnProperty(
        name = "ledgerops.payment.result-consumer.enabled",
        havingValue = "true",
        matchIfMissing = true
)
class ProviderResultConsumer {

    static final String CONSUMER_NAME = ApplyProviderResult.CONSUMER_NAME;
    private final ProviderResultEnvelopeParser parser = new ProviderResultEnvelopeParser();
    private final ConsumerMessageStore messages;
    private final ApplyProviderResult application;
    private final MeterRegistry meters;

    ProviderResultConsumer(
            ConsumerMessageStore messages,
            ApplyProviderResult application,
            MeterRegistry meters
    ) {
        this.messages = messages;
        this.application = application;
        this.meters = meters;
    }

    @KafkaListener(
            topics = "ledgerops.provider.results.v1",
            groupId = CONSUMER_NAME,
            containerFactory = "paymentResultKafkaListenerContainerFactory",
            properties = "spring.json.value.default.type=java.lang.String"
    )
    void receive(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        String raw = record.value() == null ? "" : record.value();
        UUID messageId = parser.trustworthyMessageId(raw);
        if (messageId == null) {
            messages.recordTransportDeadLetter(
                    CONSUMER_NAME,
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    sha256(raw),
                    null,
                    "INVALID_ENVELOPE",
                    "No trustworthy messageId",
                    null
            );
            recordDead("transport_invalid");
            acknowledgment.acknowledge();
            return;
        }

        ProviderResultEnvelope envelope;
        try {
            envelope = parser.parse(raw);
        } catch (InvalidProviderResultMessageException exception) {
            IncomingMessage malformed = new IncomingMessage(
                    CONSUMER_NAME,
                    messageId,
                    null,
                    "INVALID_ENVELOPE"
            );
            deadOrRetry(
                    record,
                    raw,
                    malformed,
                    sha256(raw),
                    "PERMANENTLY_INVALID_ENVELOPE",
                    true,
                    null,
                    acknowledgment
            );
            return;
        }

        IncomingMessage incoming = new IncomingMessage(
                CONSUMER_NAME,
                envelope.messageId(),
                envelope.tenantId(),
                envelope.messageType()
        );
        if (envelope.schemaVersion() != 1) {
            deadOrRetry(
                    record, raw, incoming, sha256(envelope.canonicalPayload()),
                    "UNSUPPORTED_SCHEMA_VERSION", true, envelope.correlationId(),
                    acknowledgment
            );
            return;
        }
        if (!"ProviderResultObserved".equals(envelope.messageType())) {
            deadOrRetry(
                    record, raw, incoming, sha256(envelope.canonicalPayload()),
                    "UNSUPPORTED_MESSAGE_TYPE", true, envelope.correlationId(),
                    acknowledgment
            );
            return;
        }
        if (!envelope.aggregateId().toString().equals(record.key())) {
            deadOrRetry(
                    record, raw, incoming, sha256(envelope.canonicalPayload()),
                    "PARTITION_KEY_MISMATCH", true, envelope.correlationId(),
                    acknowledgment
            );
            return;
        }

        try {
            application.apply(incoming, command(envelope));
            acknowledgment.acknowledge();
        } catch (InvalidProviderResultMessageException exception) {
            deadOrRetry(
                    record, raw, incoming, sha256(envelope.canonicalPayload()),
                    "PERMANENTLY_INVALID_PAYLOAD", true, envelope.correlationId(),
                    acknowledgment
            );
        } catch (PaymentProviderResultConsistencyException exception) {
            deadOrRetry(
                    record, raw, incoming, sha256(envelope.canonicalPayload()),
                    "RESULT_CONSISTENCY_FAILURE", true, envelope.correlationId(),
                    acknowledgment
            );
        } catch (RuntimeException exception) {
            deadOrRetry(
                    record, raw, incoming, sha256(envelope.canonicalPayload()),
                    "BUSINESS_PROCESSING_FAILURE", false, envelope.correlationId(),
                    acknowledgment
            );
        }
    }

    private PaymentProviderResultCommand command(ProviderResultEnvelope envelope) {
        JsonNode payload = envelope.payload();
        UUID paymentId = parser.canonicalUuid(payload, "paymentId");
        if (!paymentId.equals(envelope.aggregateId())) {
            throw new InvalidProviderResultMessageException(
                    "Aggregate ID must equal Payment ID"
            );
        }
        String providerId = parser.text(payload, "providerId");
        if (!"SIMULATOR".equals(providerId)) {
            throw new InvalidProviderResultMessageException(
                    "Release 0.2 provider must be SIMULATOR"
            );
        }
        String providerIdempotencyKey = parser.text(
                payload,
                "providerIdempotencyKey"
        );
        if (!providerIdempotencyKey.equals("payment:" + paymentId)) {
            throw new InvalidProviderResultMessageException(
                    "Provider idempotency key is invalid"
            );
        }
        ProviderResultCategory category = enumValue(
                ProviderResultCategory.class,
                parser.text(payload, "providerResultCategory"),
                "Provider result category"
        );
        RetryDisposition disposition = enumValue(
                RetryDisposition.class,
                parser.text(payload, "retryDisposition"),
                "Retry disposition"
        );
        String origin = parser.text(payload, "evidenceOrigin");
        if (!origin.equals("SUBMISSION_RESPONSE")
                && !origin.equals("STATUS_QUERY")
                && !origin.equals("WEBHOOK")) {
            throw new InvalidProviderResultMessageException("Evidence origin is invalid");
        }
        return new PaymentProviderResultCommand(
                envelope.messageId(),
                envelope.tenantId(),
                paymentId,
                parser.canonicalUuid(payload, "attemptId"),
                parser.canonicalUuid(payload, "providerEvidenceId"),
                parser.canonicalUuid(payload, "providerResultId"),
                providerId,
                providerIdempotencyKey,
                category,
                disposition,
                parser.optionalText(payload, "providerReference"),
                origin,
                parser.canonicalInstant(payload, "observedAt"),
                envelope.correlationId()
        );
    }

    private <T extends Enum<T>> T enumValue(
            Class<T> type,
            String value,
            String label
    ) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw new InvalidProviderResultMessageException(label + " is invalid", exception);
        }
    }

    private void deadOrRetry(
            ConsumerRecord<String, String> record,
            String raw,
            IncomingMessage incoming,
            String payloadHash,
            String code,
            boolean immediatelyDead,
            UUID correlationId,
            Acknowledgment acknowledgment
    ) {
        ConsumerFailureResult failure = messages.recordFailure(
                incoming,
                raw,
                payloadHash,
                record.topic(),
                record.partition(),
                record.offset(),
                code,
                code,
                correlationId,
                immediatelyDead
        );
        if (failure.dead()) {
            recordDead(code.toLowerCase(Locale.ROOT));
            acknowledgment.acknowledge();
        } else {
            acknowledgment.nack(Duration.ofSeconds(1));
        }
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
}
