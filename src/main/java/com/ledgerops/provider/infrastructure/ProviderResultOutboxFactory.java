package com.ledgerops.provider.infrastructure;

import com.ledgerops.messaging.api.OutboxMessageDraft;
import com.ledgerops.messaging.api.ProducerName;
import com.ledgerops.provider.api.ProviderOperationType;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;

final class ProviderResultOutboxFactory {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ProviderResultOutboxFactory() {
    }

    static OutboxMessageDraft draft(
            UUID tenantId,
            UUID paymentId,
            UUID attemptId,
            UUID evidenceId,
            UUID providerResultId,
            String providerIdempotencyKey,
            String providerReference,
            String category,
            String disposition,
            String origin,
            Instant observedAt,
            UUID correlationId,
            UUID causationId,
            Instant occurredAt,
            String traceparent,
            String tracestate
    ) {
        return draft(
                tenantId,
                paymentId,
                ProviderOperationType.PAYMENT,
                paymentId,
                attemptId,
                evidenceId,
                providerResultId,
                providerIdempotencyKey,
                providerReference,
                category,
                disposition,
                origin,
                observedAt,
                correlationId,
                causationId,
                occurredAt,
                traceparent,
                tracestate
        );
    }

    static OutboxMessageDraft draft(
            UUID tenantId,
            UUID paymentId,
            ProviderOperationType operationType,
            UUID operationId,
            UUID attemptId,
            UUID evidenceId,
            UUID providerResultId,
            String providerIdempotencyKey,
            String providerReference,
            String category,
            String disposition,
            String origin,
            Instant observedAt,
            UUID correlationId,
            UUID causationId,
            Instant occurredAt,
            String traceparent,
            String tracestate
    ) {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put("attemptId", attemptId.toString());
        fields.put("evidenceOrigin", origin);
        fields.put("observedAt", observedAt.toString());
        fields.put("paymentId", paymentId.toString());
        if (operationType == ProviderOperationType.REVERSAL) {
            fields.put("operationType", operationType.name());
            fields.put("reversalId", operationId.toString());
        }
        fields.put("providerEvidenceId", evidenceId.toString());
        fields.put("providerId", "SIMULATOR");
        fields.put("providerIdempotencyKey", providerIdempotencyKey);
        if (providerReference != null) fields.put("providerReference", providerReference);
        fields.put("providerResultId", providerResultId.toString());
        fields.put("providerResultCategory", category);
        fields.put("retryDisposition", disposition);
        String payload;
        try {
            payload = JSON.writeValueAsString(fields);
        } catch (Exception exception) {
            throw new IllegalStateException("Provider result payload cannot be encoded", exception);
        }
        String messageType = operationType == ProviderOperationType.REVERSAL
                ? "ProviderReversalResultObserved" : "ProviderResultObserved";
        return new OutboxMessageDraft(
                ProducerName.PROVIDER,
                "provider-result:" + tenantId + ":SIMULATOR:" + providerResultId,
                messageType, 1, paymentId, tenantId,
                "ledgerops.provider.results.v1", paymentId.toString(), payload,
                correlationId, causationId, occurredAt, traceparent, tracestate);
    }
}
