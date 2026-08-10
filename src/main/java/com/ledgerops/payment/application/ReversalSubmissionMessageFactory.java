package com.ledgerops.payment.application;

import com.ledgerops.messaging.api.OutboxMessageDraft;
import com.ledgerops.messaging.api.ProducerName;
import com.ledgerops.payment.domain.PaymentAttempt;
import com.ledgerops.payment.domain.Reversal;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;

final class ReversalSubmissionMessageFactory {

    static final String MESSAGE_TYPE = "SubmitReversalToProvider";
    static final String TOPIC = "ledgerops.provider.commands.v1";

    private ReversalSubmissionMessageFactory() {
    }

    static OutboxMessageDraft draft(
            PaymentAttempt attempt,
            Reversal reversal,
            String originalProviderReference,
            UUID correlationId,
            UUID causationId,
            Instant occurredAt
    ) {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put("attemptId", attempt.attemptId().value().toString());
        fields.put("paymentId", attempt.paymentId().value().toString());
        fields.put("reversalId", reversal.id().value().toString());
        fields.put("operationType", "REVERSAL");
        fields.put("attemptSequence", attempt.sequence());
        fields.put("providerId", attempt.providerId().name());
        fields.put("providerIdempotencyKey", attempt.providerIdempotencyKey());
        fields.put("amount", attempt.amount().amount().toPlainString());
        fields.put("currency", attempt.amount().currency().getCurrencyCode());
        fields.put("paymentMethodCategory", attempt.paymentMethodCategory().value());
        fields.put("merchantId", reversal.merchantId().toString());
        fields.put("originalProviderReference", originalProviderReference);
        fields.put("requestIntentHash", attempt.requestIntentHash());
        return new OutboxMessageDraft(
                ProducerName.PAYMENT,
                "reversal-submission:" + attempt.attemptId().value(),
                MESSAGE_TYPE,
                1,
                attempt.paymentId().value(),
                attempt.tenantId(),
                TOPIC,
                attempt.paymentId().value().toString(),
                CanonicalJson.object(fields),
                correlationId,
                causationId,
                occurredAt
        );
    }
}
