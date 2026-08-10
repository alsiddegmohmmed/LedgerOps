package com.ledgerops.casework.application;

import com.ledgerops.casework.domain.CaseFile;
import com.ledgerops.messaging.api.OutboxMessageDraft;
import com.ledgerops.messaging.api.ProducerName;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;

final class CaseLifecycleEventFactory {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private CaseLifecycleEventFactory() { }

    static OutboxMessageDraft draft(CaseFile file, String eventType, Instant occurredAt,
                                    UUID correlationId, UUID causationId) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", eventType);
        payload.put("caseId", file.caseId().toString());
        payload.put("tenantId", file.tenantId().toString());
        payload.put("sourceCategory", file.sourceCategory().name());
        payload.put("sourceId", file.sourceId().toString());
        payload.put("relatedPaymentId", file.relatedPaymentId() == null
                ? null : file.relatedPaymentId().toString());
        payload.put("status", file.status().name());
        payload.put("severity", file.severity().name());
        payload.put("ownerId", file.ownerId() == null ? null : file.ownerId().toString());
        payload.put("dueAt", file.dueAt().toString());
        payload.put("resolution", file.resolution() == null ? null : file.resolution().name());
        payload.put("occurredAt", occurredAt.toString());
        try {
            return new OutboxMessageDraft(
                    ProducerName.CASEWORK,
                    "case-event:" + file.caseId() + ":" + file.history().size(),
                    "CaseLifecycleChanged", 1, file.caseId(), file.tenantId(),
                    "ledgerops.casework.lifecycle.v1", file.caseId().toString(),
                    JSON.writeValueAsString(payload), correlationId, causationId, occurredAt);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot serialize Case lifecycle event", exception);
        }
    }
}
