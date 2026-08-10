package com.ledgerops.reporting.infrastructure;

import com.ledgerops.reporting.api.PaymentTimelineEntry;
import com.ledgerops.reporting.application.PaymentTimelineProjector;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PaymentLifecycleProjectionConsumerTests {

    @Test
    void reversalLifecycleUsesPayloadPaymentAndReversalIdentities() {
        PaymentTimelineProjector projector = mock(PaymentTimelineProjector.class);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        UUID messageId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID reversalId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        String raw = """
                {
                  "messageId":"%s",
                  "tenantId":"%s",
                  "aggregateId":"%s",
                  "messageType":"ReversalCompleted",
                  "occurredAt":"2026-08-10T10:15:30Z",
                  "correlationId":"%s",
                  "payload":{
                    "reversalId":"%s",
                    "paymentId":"%s",
                    "merchantId":"%s",
                    "status":"COMPLETED"
                  }
                }
                """.formatted(
                messageId, tenantId, reversalId, UUID.randomUUID(), reversalId,
                paymentId, merchantId);

        new PaymentLifecycleProjectionConsumer(projector)
                .receive(new ConsumerRecord<>(
                        "ledgerops.payment.lifecycle.v1", 0, 0L, paymentId.toString(), raw),
                        acknowledgment);

        var entry = org.mockito.ArgumentCaptor.forClass(PaymentTimelineEntry.class);
        verify(projector).project(entry.capture());
        PaymentTimelineEntry projected = entry.getValue();
        org.junit.jupiter.api.Assertions.assertEquals(paymentId, projected.paymentId());
        org.junit.jupiter.api.Assertions.assertEquals(reversalId, projected.sourceId());
        org.junit.jupiter.api.Assertions.assertEquals("PAYMENT", projected.sourceModule());
        org.junit.jupiter.api.Assertions.assertEquals("ReversalCompleted", projected.sourceType());
        org.junit.jupiter.api.Assertions.assertEquals("Reversal completed", projected.displayText());
        verify(acknowledgment).acknowledge();
    }
}
