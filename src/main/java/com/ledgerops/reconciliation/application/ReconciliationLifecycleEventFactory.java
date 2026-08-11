package com.ledgerops.reconciliation.application;

import com.ledgerops.messaging.api.OutboxMessageDraft;
import com.ledgerops.messaging.api.ProducerName;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;

public final class ReconciliationLifecycleEventFactory {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ReconciliationLifecycleEventFactory() {
    }

    public static OutboxMessageDraft runChanged(
            UUID tenantId,
            UUID batchFamilyId,
            UUID runId,
            String event,
            String status,
            long matchedCount,
            long unmatchedCount,
            long discrepancyCount,
            Instant occurredAt
    ) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("aggregateType", "ReconciliationRun");
        payload.put("aggregateId", runId.toString());
        payload.put("event", event);
        payload.put("runId", runId.toString());
        payload.put("batchFamilyId", batchFamilyId.toString());
        payload.put("status", status);
        payload.put("matchedCount", matchedCount);
        payload.put("unmatchedCount", unmatchedCount);
        payload.put("discrepancyCount", discrepancyCount);
        payload.put("occurredAt", occurredAt.toString());
        return draft(
                "reconciliation-event:RUN:" + runId + ":" + event,
                runId, tenantId, batchFamilyId.toString(), payload, runId, occurredAt);
    }

    public static OutboxMessageDraft currentRunPromoted(
            UUID tenantId,
            UUID batchFamilyId,
            UUID runId,
            Instant occurredAt
    ) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("aggregateType", "CurrentReconciliationRun");
        payload.put("aggregateId", batchFamilyId.toString());
        payload.put("event", "CURRENT_RUN_PROMOTED");
        payload.put("batchFamilyId", batchFamilyId.toString());
        payload.put("runId", runId.toString());
        payload.put("promotedAt", occurredAt.toString());
        return draft(
                "reconciliation-event:CURRENT_RUN:" + batchFamilyId + ":" + runId,
                runId, tenantId, batchFamilyId.toString(), payload, runId, occurredAt);
    }

    public static OutboxMessageDraft postingChanged(
            UUID tenantId,
            UUID batchFamilyId,
            UUID runId,
            UUID settlementPostingId,
            String event,
            String status,
            String subjectType,
            UUID subjectId,
            UUID ledgerTransactionId,
            String failureCode,
            Instant occurredAt
    ) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("aggregateType", "SettlementPosting");
        payload.put("aggregateId", settlementPostingId.toString());
        payload.put("event", event);
        payload.put("settlementPostingId", settlementPostingId.toString());
        payload.put("runId", runId.toString());
        payload.put("batchFamilyId", batchFamilyId.toString());
        payload.put("subjectType", subjectType);
        payload.put("subjectId", subjectId.toString());
        payload.put("status", status);
        payload.put("ledgerTransactionId", ledgerTransactionId == null
                ? null : ledgerTransactionId.toString());
        payload.put("failureCode", failureCode);
        payload.put("occurredAt", occurredAt.toString());
        return draft(
                "reconciliation-event:POSTING:" + settlementPostingId + ":" + event,
                settlementPostingId, tenantId, settlementPostingId.toString(), payload,
                settlementPostingId, occurredAt);
    }

    private static OutboxMessageDraft draft(
            String deduplicationKey,
            UUID aggregateId,
            UUID tenantId,
            String partitionKey,
            LinkedHashMap<String, Object> payload,
            UUID causationId,
            Instant occurredAt
    ) {
        try {
            return new OutboxMessageDraft(
                    ProducerName.RECONCILIATION,
                    deduplicationKey,
                    "ReconciliationLifecycleChanged",
                    1,
                    aggregateId,
                    tenantId,
                    "ledgerops.reconciliation.lifecycle.v1",
                    partitionKey,
                    JSON.writeValueAsString(payload),
                    causationId,
                    causationId,
                    occurredAt);
        } catch (Exception exception) {
            throw new IllegalStateException("Reconciliation lifecycle payload cannot be encoded", exception);
        }
    }
}
