package com.ledgerops.casework.infrastructure;

import com.ledgerops.casework.api.CaseCreationRequest;
import com.ledgerops.casework.domain.CaseSeverity;
import com.ledgerops.casework.domain.CaseSourceCategory;
import com.ledgerops.casework.application.CaseApplicationService;
import com.ledgerops.messaging.api.ConsumerFailureResult;
import com.ledgerops.messaging.api.ConsumerMessageStore;
import com.ledgerops.messaging.api.IncomingMessage;
import com.ledgerops.messaging.api.MessageEnvelopeDecodeException;
import com.ledgerops.messaging.api.MessageEnvelopeDecoder;
import com.ledgerops.messaging.api.MessageEnvelopeView;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "ledgerops.casework.create-consumer.enabled",
        havingValue = "true", matchIfMissing = true)
class CaseworkCreateCommandConsumer {
    static final String CONSUMER_NAME = "casework-create-command-consumer-v1";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final MessageEnvelopeDecoder codec;
    private final ConsumerMessageStore messages;
    private final CaseApplicationService cases;

    CaseworkCreateCommandConsumer(MessageEnvelopeDecoder codec, ConsumerMessageStore messages,
                                   CaseApplicationService cases) {
        this.codec = codec;
        this.messages = messages;
        this.cases = cases;
    }

    @KafkaListener(
            topics = "ledgerops.casework.commands.v1",
            groupId = CONSUMER_NAME,
            containerFactory = "caseworkCommandKafkaListenerContainerFactory",
            properties = "spring.json.value.default.type=java.lang.String"
    )
    void receive(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        String raw = record.value() == null ? "" : record.value();
        UUID trustworthyId = codec.trustworthyMessageId(raw);
        if (trustworthyId == null) {
            messages.recordTransportDeadLetter(CONSUMER_NAME, record.topic(), record.partition(),
                    record.offset(), sha256(raw), null, "INVALID_ENVELOPE",
                    "No trustworthy messageId", null);
            acknowledgment.acknowledge();
            return;
        }
        MessageEnvelopeView envelope;
        try {
            envelope = codec.decodeForConsumer(raw);
        } catch (MessageEnvelopeDecodeException exception) {
            dead(record, raw, new IncomingMessage(CONSUMER_NAME, trustworthyId, null,
                    "INVALID_ENVELOPE"), "INVALID_ENVELOPE", null, acknowledgment);
            return;
        }
        IncomingMessage incoming = new IncomingMessage(CONSUMER_NAME, envelope.messageId(),
                envelope.tenantId(), envelope.messageType());
        try {
            if (envelope.schemaVersion() != 1) throw new MessageEnvelopeDecodeException("Unsupported schema version");
            if (!"CreateCaseRequested".equals(envelope.messageType())) {
                throw new MessageEnvelopeDecodeException("Unsupported Casework command");
            }
            if (!envelope.aggregateId().toString().equals(record.key())) {
                throw new MessageEnvelopeDecodeException("Case command partition key mismatch");
            }
            cases.applyCreateCommand(incoming, request(envelope));
            acknowledgment.acknowledge();
        } catch (MessageEnvelopeDecodeException | IllegalArgumentException exception) {
            dead(record, raw, incoming, "INVALID_CASE_COMMAND", envelope.correlationId(), acknowledgment);
        } catch (RuntimeException exception) {
            ConsumerFailureResult failure = messages.recordFailure(incoming, raw,
                    sha256(raw), record.topic(), record.partition(), record.offset(),
                    "CASE_COMMAND_PROCESSING_FAILURE", "Case command processing failed",
                    envelope.correlationId(), false);
            if (failure.dead()) acknowledgment.acknowledge();
            else acknowledgment.nack(java.time.Duration.ofSeconds(1));
        }
    }

    private CaseCreationRequest request(MessageEnvelopeView envelope) {
        JsonNode payload;
        try {
            payload = JSON.readTree(envelope.payloadJson());
        } catch (Exception exception) {
            throw new MessageEnvelopeDecodeException("Case command payload is not valid JSON", exception);
        }
        if (payload == null || !payload.isObject()) {
            throw new MessageEnvelopeDecodeException("Case command payload must be an object");
        }
        UUID caseId = uuid(payload, "caseId");
        if (!caseId.equals(envelope.aggregateId())) throw new MessageEnvelopeDecodeException("Case ID must equal aggregate ID");
        UUID tenantId = uuid(payload, "tenantId");
        if (!tenantId.equals(envelope.tenantId())) throw new MessageEnvelopeDecodeException("Tenant ID mismatch");
        CaseSourceCategory source = enumValue(CaseSourceCategory.class, text(payload, "sourceCategory"));
        CaseSeverity severity = enumValue(CaseSeverity.class, text(payload, "severity"));
        return new CaseCreationRequest(caseId, tenantId, source, uuid(payload, "sourceId"),
                optionalUuid(payload, "paymentId"), severity, instant(payload, "dueAt"));
    }

    private void dead(ConsumerRecord<String, String> record, String raw, IncomingMessage incoming,
                      String reason, UUID correlationId, Acknowledgment acknowledgment) {
        messages.recordFailure(incoming, raw, sha256(raw), record.topic(), record.partition(),
                record.offset(), reason, reason, correlationId, true);
        acknowledgment.acknowledge();
    }

    private String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isString() || value.asString().isBlank())
            throw new MessageEnvelopeDecodeException(name + " must be nonblank");
        return value.asString();
    }

    private UUID uuid(JsonNode node, String name) {
        return UUID.fromString(text(node, name));
    }

    private UUID optionalUuid(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value == null || value.isNull() ? null : uuid(node, name);
    }

    private Instant instant(JsonNode node, String name) {
        return Instant.parse(text(node, name));
    }

    private <T extends Enum<T>> T enumValue(Class<T> type, String value) {
        return Enum.valueOf(type, value);
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
