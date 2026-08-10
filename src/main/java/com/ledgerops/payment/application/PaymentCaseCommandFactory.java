package com.ledgerops.payment.application;

import com.ledgerops.messaging.api.OutboxMessageDraft;
import com.ledgerops.messaging.api.ProducerName;
import com.ledgerops.payment.domain.Payment;
import com.ledgerops.risk.api.RiskReviewSnapshot;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;

final class PaymentCaseCommandFactory {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String RISK_ESCALATION_CASE_SEVERITY = "HIGH";

    private PaymentCaseCommandFactory() { }

    static OutboxMessageDraft createRiskReviewCase(
            Payment payment,
            RiskReviewSnapshot review,
            UUID correlationId,
            UUID causationId,
            Instant occurredAt
    ) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("caseId", review.caseId().toString());
        payload.put("tenantId", payment.tenantId().toString());
        payload.put("sourceCategory", "RISK_REVIEW");
        payload.put("sourceId", review.reviewId().toString());
        payload.put("paymentId", payment.id().value().toString());
        payload.put("severity", RISK_ESCALATION_CASE_SEVERITY);
        payload.put("dueAt", review.dueAt().toString());
        payload.put("riskReviewId", review.reviewId().toString());
        payload.put("requestedAt", occurredAt.toString());
        try {
            return new OutboxMessageDraft(
                    ProducerName.PAYMENT,
                    "case-request:RISK_REVIEW:" + review.reviewId(),
                    "CreateCaseRequested", 1, review.caseId(), payment.tenantId(),
                    "ledgerops.casework.commands.v1", review.caseId().toString(),
                    JSON.writeValueAsString(payload), correlationId, causationId, occurredAt);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot serialize Case command", exception);
        }
    }
}
