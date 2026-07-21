package com.ledgerops.payment.infrastructure;

import com.ledgerops.messaging.api.ConsumerFailureResult;
import com.ledgerops.messaging.api.ConsumerMessageStore;
import com.ledgerops.messaging.api.IncomingMessage;
import com.ledgerops.payment.application.ApplyPaymentSubmissionRetry;
import com.ledgerops.payment.application.PaymentRetryConsistencyException;
import com.ledgerops.payment.application.PaymentSubmissionRetryCommand;
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
import java.util.UUID;

@Component
@ConditionalOnProperty(
        name = "ledgerops.payment.retry-consumer.enabled",
        havingValue = "true",
        matchIfMissing = true
)
class PaymentRetryCommandConsumer {
    static final String CONSUMER_NAME = ApplyPaymentSubmissionRetry.CONSUMER_NAME;
    private final ProviderResultEnvelopeParser parser = new ProviderResultEnvelopeParser();
    private final ConsumerMessageStore messages;
    private final ApplyPaymentSubmissionRetry application;

    PaymentRetryCommandConsumer(
            ConsumerMessageStore messages,
            ApplyPaymentSubmissionRetry application
    ) {
        this.messages = messages;
        this.application = application;
    }

    @KafkaListener(
            topics = "ledgerops.payment.commands.v1",
            groupId = CONSUMER_NAME,
            containerFactory = "paymentResultKafkaListenerContainerFactory",
            properties = "spring.json.value.default.type=java.lang.String"
    )
    void receive(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        String raw = record.value() == null ? "" : record.value();
        UUID messageId = parser.trustworthyMessageId(raw);
        if (messageId == null) {
            messages.recordTransportDeadLetter(
                    CONSUMER_NAME, record.topic(), record.partition(), record.offset(),
                    sha256(raw), null, "INVALID_ENVELOPE", "No trustworthy messageId", null);
            acknowledgment.acknowledge();
            return;
        }
        ProviderResultEnvelope envelope;
        try {
            envelope = parser.parse(raw);
        } catch (InvalidProviderResultMessageException exception) {
            fail(record, raw, new IncomingMessage(
                    CONSUMER_NAME, messageId, null, "INVALID_ENVELOPE"), sha256(raw),
                    "PERMANENTLY_INVALID_ENVELOPE", true, null, acknowledgment);
            return;
        }
        IncomingMessage incoming = new IncomingMessage(
                CONSUMER_NAME, envelope.messageId(), envelope.tenantId(), envelope.messageType());
        if (envelope.schemaVersion() != 1) {
            fail(record, raw, incoming, sha256(envelope.canonicalPayload()),
                    "UNSUPPORTED_SCHEMA_VERSION", true, envelope.correlationId(), acknowledgment);
            return;
        }
        if (!"PaymentSubmissionRetryRequested".equals(envelope.messageType())) {
            fail(record, raw, incoming, sha256(envelope.canonicalPayload()),
                    "UNSUPPORTED_MESSAGE_TYPE", true, envelope.correlationId(), acknowledgment);
            return;
        }
        if (!envelope.aggregateId().toString().equals(record.key())) {
            fail(record, raw, incoming, sha256(envelope.canonicalPayload()),
                    "PARTITION_KEY_MISMATCH", true, envelope.correlationId(), acknowledgment);
            return;
        }
        try {
            application.apply(incoming, command(envelope));
            acknowledgment.acknowledge();
        } catch (InvalidProviderResultMessageException | PaymentRetryConsistencyException exception) {
            fail(record, raw, incoming, sha256(envelope.canonicalPayload()),
                    "PERMANENTLY_INVALID_RETRY", true, envelope.correlationId(), acknowledgment);
        } catch (RuntimeException exception) {
            fail(record, raw, incoming, sha256(envelope.canonicalPayload()),
                    "BUSINESS_PROCESSING_FAILURE", false, envelope.correlationId(), acknowledgment);
        }
    }

    private PaymentSubmissionRetryCommand command(ProviderResultEnvelope envelope) {
        JsonNode payload = envelope.payload();
        UUID paymentId = parser.canonicalUuid(payload, "paymentId");
        if (!paymentId.equals(envelope.aggregateId())) {
            throw new InvalidProviderResultMessageException("Aggregate ID must equal Payment ID");
        }
        String providerId = parser.text(payload, "providerId");
        if (!"SIMULATOR".equals(providerId)) {
            throw new InvalidProviderResultMessageException(
                    "Release 0.2 provider must be SIMULATOR");
        }
        return new PaymentSubmissionRetryCommand(
                envelope.messageId(), parser.canonicalUuid(payload, "retryRequestId"),
                envelope.tenantId(), paymentId,
                parser.canonicalUuid(payload, "previousAttemptId"),
                parser.canonicalUuid(payload, "providerEvidenceId"), providerId,
                parser.canonicalInstant(payload, "requestedAt"), envelope.correlationId());
    }

    private void fail(
            ConsumerRecord<String, String> record, String raw, IncomingMessage incoming,
            String payloadHash, String code, boolean immediatelyDead, UUID correlationId,
            Acknowledgment acknowledgment
    ) {
        ConsumerFailureResult result = messages.recordFailure(
                incoming, raw, payloadHash, record.topic(), record.partition(), record.offset(),
                code, code, correlationId, immediatelyDead);
        if (result.dead()) {
            acknowledgment.acknowledge();
        } else {
            acknowledgment.nack(Duration.ofSeconds(1));
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
