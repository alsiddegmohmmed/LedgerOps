package com.ledgerops.payment.application;

import com.ledgerops.messaging.api.OutboxMessageDraft;
import com.ledgerops.messaging.api.ProducerName;
import com.ledgerops.payment.domain.Reversal;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;

final class ReversalLifecycleEventFactory {

    private static final String TOPIC = "ledgerops.payment.lifecycle.v1";

    private ReversalLifecycleEventFactory() {
    }

    static OutboxMessageDraft requested(
            Reversal reversal,
            UUID correlationId,
            UUID causationId,
            Instant occurredAt
    ) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("reversalId", reversal.id().value().toString());
        payload.put("tenantId", reversal.tenantId().toString());
        payload.put("paymentId", reversal.paymentId().value().toString());
        payload.put("merchantId", reversal.merchantId().toString());
        payload.put("status", reversal.status().name());
        payload.put("amount", reversal.amount().amount().toPlainString());
        payload.put("currency", reversal.amount().currency().getCurrencyCode());
        payload.put("aggregateVersion", reversal.version());
        payload.put("requestedAt", reversal.requestedAt().toString());

        return new OutboxMessageDraft(
                ProducerName.PAYMENT,
                "reversal-lifecycle:" + reversal.id().value() + ":" + reversal.version(),
                "ReversalRequested",
                1,
                reversal.id().value(),
                reversal.tenantId(),
                TOPIC,
                reversal.paymentId().value().toString(),
                CanonicalJson.object(payload),
                correlationId,
                causationId,
                occurredAt
        );
    }

    static OutboxMessageDraft processingStarted(
            Reversal reversal,
            long aggregateVersion,
            UUID attemptId,
            UUID correlationId,
            UUID causationId,
            Instant occurredAt
    ) {
        LinkedHashMap<String, Object> payload = basePayload(reversal, aggregateVersion);
        payload.put("processingAt", reversal.processingAt().toString());
        payload.put("attemptId", attemptId.toString());
        return new OutboxMessageDraft(
                ProducerName.PAYMENT,
                "reversal-lifecycle:" + reversal.id().value() + ":" + aggregateVersion,
                "ReversalProcessingStarted",
                1,
                reversal.id().value(),
                reversal.tenantId(),
                TOPIC,
                reversal.paymentId().value().toString(),
                CanonicalJson.object(payload),
                correlationId,
                causationId,
                occurredAt
        );
    }

    static OutboxMessageDraft failed(
            Reversal reversal,
            long aggregateVersion,
            UUID attemptId,
            UUID providerEvidenceId,
            UUID correlationId,
            UUID causationId,
            Instant occurredAt
    ) {
        LinkedHashMap<String, Object> payload = basePayload(reversal, aggregateVersion);
        payload.put("failedAt", reversal.failedAt().toString());
        payload.put("failureCategory", reversal.failureCategory());
        payload.put("attemptId", attemptId.toString());
        payload.put("providerEvidenceId", providerEvidenceId.toString());
        return new OutboxMessageDraft(
                ProducerName.PAYMENT,
                "reversal-lifecycle:" + reversal.id().value() + ":" + aggregateVersion,
                "ReversalFailed",
                1,
                reversal.id().value(),
                reversal.tenantId(),
                TOPIC,
                reversal.paymentId().value().toString(),
                CanonicalJson.object(payload),
                correlationId,
                causationId,
                occurredAt
        );
    }

    static OutboxMessageDraft completed(
            Reversal reversal,
            long aggregateVersion,
            UUID attemptId,
            UUID providerEvidenceId,
            UUID ledgerTransactionId,
            UUID correlationId,
            UUID causationId,
            Instant occurredAt
    ) {
        LinkedHashMap<String, Object> payload = basePayload(reversal, aggregateVersion);
        payload.put("completedAt", reversal.completedAt().toString());
        payload.put("attemptId", attemptId.toString());
        payload.put("providerEvidenceId", providerEvidenceId.toString());
        payload.put("ledgerTransactionId", ledgerTransactionId.toString());
        return new OutboxMessageDraft(
                ProducerName.PAYMENT,
                "reversal-lifecycle:" + reversal.id().value() + ":" + aggregateVersion,
                "ReversalCompleted",
                1,
                reversal.id().value(),
                reversal.tenantId(),
                TOPIC,
                reversal.paymentId().value().toString(),
                CanonicalJson.object(payload),
                correlationId,
                causationId,
                occurredAt
        );
    }

    private static LinkedHashMap<String, Object> basePayload(
            Reversal reversal,
            long aggregateVersion
    ) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("reversalId", reversal.id().value().toString());
        payload.put("tenantId", reversal.tenantId().toString());
        payload.put("paymentId", reversal.paymentId().value().toString());
        payload.put("merchantId", reversal.merchantId().toString());
        payload.put("status", reversal.status().name());
        payload.put("amount", reversal.amount().amount().toPlainString());
        payload.put("currency", reversal.amount().currency().getCurrencyCode());
        payload.put("aggregateVersion", aggregateVersion);
        return payload;
    }
}
