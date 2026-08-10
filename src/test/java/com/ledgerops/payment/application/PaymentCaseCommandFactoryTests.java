package com.ledgerops.payment.application;

import com.ledgerops.merchant.api.MerchantReference;
import com.ledgerops.messaging.api.OutboxMessageDraft;
import com.ledgerops.payment.domain.CustomerId;
import com.ledgerops.payment.domain.IdempotencyKey;
import com.ledgerops.payment.domain.Money;
import com.ledgerops.payment.domain.Payment;
import com.ledgerops.payment.domain.PaymentId;
import com.ledgerops.payment.domain.PaymentMethodCategory;
import com.ledgerops.risk.api.RiskReviewDecision;
import com.ledgerops.risk.api.RiskReviewSnapshot;
import com.ledgerops.risk.api.RiskReviewStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaymentCaseCommandFactoryTests {
    private static final Instant CREATED = Instant.parse("2026-08-10T10:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void riskEscalationUsesHighSeverityAndCarriesTheRiskDueTime() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        Payment payment = Payment.create(
                PaymentId.from(paymentId), MerchantReference.from(tenantId, merchantId),
                CustomerId.from(UUID.randomUUID()),
                Money.of(new BigDecimal("10.00"), Currency.getInstance("SAR")),
                PaymentMethodCategory.from("card"), IdempotencyKey.from("case-command-test"))
                .startValidation()
                .requestRiskReview();
        Instant dueAt = CREATED.plusSeconds(24 * 60 * 60);
        RiskReviewSnapshot review = new RiskReviewSnapshot(
                UUID.randomUUID(), tenantId, paymentId, merchantId, UUID.randomUUID(),
                RiskReviewStatus.ESCALATED, UUID.randomUUID(), 0, 1, CREATED, dueAt,
                RiskReviewDecision.ESCALATE, "Needs investigation", caseId,
                CREATED.plusSeconds(5), 1);

        OutboxMessageDraft command = PaymentCaseCommandFactory.createRiskReviewCase(
                payment, review, UUID.randomUUID(), UUID.randomUUID(), CREATED.plusSeconds(5));

        var payload = JSON.readTree(command.canonicalPayloadJson());
        assertEquals("HIGH", payload.get("severity").asText());
        assertEquals(dueAt.toString(), payload.get("dueAt").asText());
    }
}
