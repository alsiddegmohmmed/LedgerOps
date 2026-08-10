package com.ledgerops.payment.application;

import com.ledgerops.messaging.api.OutboxMessageDraft;
import com.ledgerops.messaging.api.ProducerName;
import com.ledgerops.payment.domain.Payment;
import com.ledgerops.payment.domain.PaymentStatus;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;

final class PaymentLifecycleEventFactory {

    static final String MESSAGE_TYPE = "PaymentLifecycleChanged";
    static final String TOPIC = "ledgerops.payment.lifecycle.v1";

    private PaymentLifecycleEventFactory() {
    }

    static OutboxMessageDraft draft(
            Payment before,
            Payment after,
            long aggregateVersion,
            String actorSource,
            String reasonCode,
            UUID correlationId,
            UUID causationId,
            Instant occurredAt
    ) {
        if (before.status() == after.status()) {
            throw new IllegalArgumentException("Payment lifecycle event requires a status change");
        }
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("paymentId", after.id().value().toString());
        payload.put("tenantId", after.tenantId().toString());
        payload.put("fromStatus", before.status().name());
        payload.put("toStatus", after.status().name());
        payload.put("aggregateVersion", aggregateVersion);
        payload.put("actorSource", actorSource);
        payload.put("reasonCode", reasonCode);
        payload.put("correlationId", correlationId.toString());
        payload.put("causationId", causationId.toString());
        payload.put("occurredAt", occurredAt.toString());
        return new OutboxMessageDraft(
                ProducerName.PAYMENT,
                "payment-lifecycle:" + after.id().value() + ":" + aggregateVersion,
                MESSAGE_TYPE,
                1,
                after.id().value(),
                after.tenantId(),
                TOPIC,
                after.id().value().toString(),
                CanonicalJson.object(payload),
                correlationId,
                causationId,
                occurredAt);
    }

    static UUID deterministicCorrelation(Payment payment, long aggregateVersion) {
        return UUID.nameUUIDFromBytes(("payment-lifecycle-correlation:"
                + payment.id().value() + ":" + aggregateVersion)
                .getBytes(StandardCharsets.UTF_8));
    }

    static UUID deterministicCausation(Payment payment, long aggregateVersion) {
        return UUID.nameUUIDFromBytes(("payment-lifecycle-causation:"
                + payment.id().value() + ":" + aggregateVersion)
                .getBytes(StandardCharsets.UTF_8));
    }
}
