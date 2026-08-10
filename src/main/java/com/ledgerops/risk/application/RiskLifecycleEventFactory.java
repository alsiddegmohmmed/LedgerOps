package com.ledgerops.risk.application;

import com.ledgerops.messaging.api.OutboxMessageDraft;
import com.ledgerops.messaging.api.ProducerName;
import com.ledgerops.risk.domain.RiskReview;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;

final class RiskLifecycleEventFactory {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private RiskLifecycleEventFactory() { }

    static OutboxMessageDraft draft(RiskReview review, String eventType, Instant occurredAt,
                                    UUID correlationId, UUID causationId) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", eventType);
        payload.put("reviewId", review.reviewId().value().toString());
        payload.put("paymentId", review.paymentId().toString());
        payload.put("tenantId", review.tenantId().toString());
        payload.put("status", review.status().name());
        payload.put("assignedAnalystId", review.assignedAnalystId() == null ? null : review.assignedAnalystId().toString());
        payload.put("decision", review.decision() == null ? null : review.decision().name());
        payload.put("caseId", review.caseId() == null ? null : review.caseId().toString());
        payload.put("priority", review.priority());
        payload.put("slaVersion", review.slaVersion());
        payload.put("dueAt", review.dueAt().toString());
        payload.put("version", review.version());
        payload.put("occurredAt", occurredAt.toString());
        try {
            return new OutboxMessageDraft(
                    ProducerName.RISK,
                    "risk-event:RISK_REVIEW:" + review.reviewId().value() + ":" + review.version(),
                    "RiskLifecycleChanged", 1, review.reviewId().value(), review.tenantId(),
                    "ledgerops.risk.lifecycle.v1", review.paymentId().toString(),
                    JSON.writeValueAsString(payload), correlationId, causationId, occurredAt);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot serialize Risk lifecycle event", exception);
        }
    }
}
