package com.ledgerops.reporting.infrastructure;

import com.ledgerops.reporting.api.PaymentTimelineEntry;
import com.ledgerops.reporting.application.PaymentTimelineProjector;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

@Component
@ConditionalOnProperty(
        name = "ledgerops.reporting.payment-timeline-consumer.enabled",
        havingValue = "true",
        matchIfMissing = true
)
class PaymentLifecycleProjectionConsumer {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final PaymentTimelineProjector projector;

    PaymentLifecycleProjectionConsumer(PaymentTimelineProjector projector) {
        this.projector = projector;
    }

    @KafkaListener(
            topics = "ledgerops.payment.lifecycle.v1",
            groupId = "reporting-payment-timeline-consumer-v1",
            containerFactory = "paymentResultKafkaListenerContainerFactory",
            properties = "spring.json.value.default.type=java.lang.String"
    )
    void receive(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        try {
            JsonNode root = JSON.readTree(record.value());
            String messageType = text(root, "messageType");
            UUID messageId = UUID.fromString(text(root, "messageId"));
            UUID tenantId = UUID.fromString(text(root, "tenantId"));
            UUID paymentId = UUID.fromString(text(root, "aggregateId"));
            JsonNode payload = root.get("payload");
            if (payload == null || !payload.isObject()) {
                throw new IllegalArgumentException("Payment lifecycle payload must be an object");
            }
            String outcome = optional(payload, "toStatus", messageType);
            String displayText = displayText(messageType, outcome);
            projector.project(new PaymentTimelineEntry(
                    messageId,
                    tenantId,
                    paymentId,
                    optionalUuid(payload, "merchantId"),
                    "PAYMENT",
                    messageType,
                    paymentId,
                    Instant.parse(text(root, "occurredAt")),
                    optional(payload, "actorSource", "AUTOMATED"),
                    outcome,
                    nullable(payload, "reasonCode"),
                    optionalUuid(root, "correlationId"),
                    displayText));
        } catch (RuntimeException ignored) {
            // Invalid lifecycle facts are acknowledged after the durable source
            // consumer has rejected them; they cannot be turned into a business fact.
        }
        acknowledgment.acknowledge();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw new IllegalArgumentException("Lifecycle envelope field is missing: " + field);
        }
        return value.asString();
    }

    private static String optional(JsonNode node, String field, String fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || !value.isString() || value.asString().isBlank()
                ? fallback : value.asString();
    }

    private static String nullable(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() || !value.isString() || value.asString().isBlank()
                ? null : value.asString();
    }

    private static UUID optionalUuid(JsonNode node, String field) {
        String value = nullable(node, field);
        return value == null ? null : UUID.fromString(value);
    }

    private static String displayText(String messageType, String outcome) {
        return switch (outcome) {
            case "COMPLETED" -> "Payment completed";
            case "FAILED" -> "Payment failed";
            default -> "Payment lifecycle event: " + messageType;
        };
    }
}
