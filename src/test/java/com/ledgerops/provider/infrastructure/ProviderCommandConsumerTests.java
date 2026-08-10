package com.ledgerops.provider.infrastructure;

import com.ledgerops.messaging.api.ConsumerFailureResult;
import com.ledgerops.messaging.api.ConsumerMessageStore;
import com.ledgerops.messaging.api.InboxResult;
import com.ledgerops.messaging.api.IncomingMessage;
import com.ledgerops.provider.application.AcceptProviderSubmissionCommand;
import com.ledgerops.provider.application.ProviderSubmissionCommand;
import com.ledgerops.provider.api.ProviderOperationType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class ProviderCommandConsumerTests {

    @Test
    void reversalCommandIsAcceptedWithTypedOperationIdentity() {
        ConsumerMessageStore messages = mock(ConsumerMessageStore.class);
        AcceptProviderSubmissionCommand acceptance = mock(AcceptProviderSubmissionCommand.class);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        UUID messageId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID reversalId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        String key = "reversal:" + reversalId;
        String raw = "{" +
                "\"messageId\":\"" + messageId + "\"," +
                "\"messageType\":\"SubmitReversalToProvider\"," +
                "\"schemaVersion\":1," +
                "\"aggregateId\":\"" + paymentId + "\"," +
                "\"tenantId\":\"" + tenantId + "\"," +
                "\"correlationId\":\"" + UUID.randomUUID() + "\"," +
                "\"causationId\":\"" + UUID.randomUUID() + "\"," +
                "\"occurredAt\":\"2026-08-10T12:00:00Z\"," +
                "\"payload\":{" +
                "\"attemptId\":\"" + attemptId + "\"," +
                "\"paymentId\":\"" + paymentId + "\"," +
                "\"reversalId\":\"" + reversalId + "\"," +
                "\"attemptSequence\":1," +
                "\"providerId\":\"SIMULATOR\"," +
                "\"providerIdempotencyKey\":\"" + key + "\"," +
                "\"amount\":\"12.30\"," +
                "\"currency\":\"SAR\"," +
                "\"paymentMethodCategory\":\"CARD\"," +
                "\"merchantId\":\"" + UUID.randomUUID() + "\"," +
                "\"originalProviderReference\":\"provider-reference-1\"," +
                "\"requestIntentHash\":\"" + "a".repeat(64) + "\"}}";
        when(acceptance.accept(any(IncomingMessage.class), any(ProviderSubmissionCommand.class)))
                .thenReturn(InboxResult.PROCESSED);
        ProviderCommandConsumer consumer = new ProviderCommandConsumer(
                messages, acceptance, new SimpleMeterRegistry()
        );

        consumer.receive(new ConsumerRecord<>(
                "ledgerops.provider.commands.v1", 0, 1, paymentId.toString(), raw),
                acknowledgment);

        ArgumentCaptor<ProviderSubmissionCommand> captor =
                ArgumentCaptor.forClass(ProviderSubmissionCommand.class);
        verify(acceptance).accept(any(IncomingMessage.class), captor.capture());
        ProviderSubmissionCommand command = captor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals(
                ProviderOperationType.REVERSAL, command.operationType());
        org.junit.jupiter.api.Assertions.assertEquals(reversalId, command.operationId());
        org.junit.jupiter.api.Assertions.assertEquals(paymentId, command.paymentId());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void malformedEnvelopeWithTrustworthyMessageIdUsesNormalConsumerDeadLetterIdentity() {
        ConsumerMessageStore messages = mock(ConsumerMessageStore.class);
        AcceptProviderSubmissionCommand acceptance = mock(AcceptProviderSubmissionCommand.class);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        UUID messageId = UUID.randomUUID();
        String raw = "{\"messageId\":\"" + messageId + "\",\"broken\":true}";
        when(messages.recordFailure(
                any(IncomingMessage.class), eq(raw), any(String.class), eq("topic"),
                eq(2), eq(41L), eq("PERMANENTLY_INVALID_ENVELOPE"),
                any(String.class), isNull(), eq(true)
        )).thenReturn(new ConsumerFailureResult(5, true));
        ProviderCommandConsumer consumer = new ProviderCommandConsumer(
                messages, acceptance, new SimpleMeterRegistry()
        );

        consumer.receive(new ConsumerRecord<>("topic", 2, 41, "key", raw), acknowledgment);

        verify(messages).recordFailure(
                any(IncomingMessage.class), eq(raw), any(String.class), eq("topic"),
                eq(2), eq(41L), eq("PERMANENTLY_INVALID_ENVELOPE"),
                any(String.class), isNull(), eq(true)
        );
        verify(messages, never()).recordTransportDeadLetter(
                any(String.class), any(String.class), anyInt(), anyLong(),
                any(String.class), any(), any(String.class), any(String.class), any()
        );
        verify(acknowledgment).acknowledge();
    }

    @Test
    void recordWithoutTrustworthyMessageIdUsesTransportDeadLetterIdentity() {
        ConsumerMessageStore messages = mock(ConsumerMessageStore.class);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        ProviderCommandConsumer consumer = new ProviderCommandConsumer(
                messages, mock(AcceptProviderSubmissionCommand.class),
                new SimpleMeterRegistry()
        );

        consumer.receive(new ConsumerRecord<>("topic", 3, 52, "key", "not-json"), acknowledgment);

        verify(messages).recordTransportDeadLetter(
                eq("provider-command-consumer-v1"), eq("topic"), eq(3), eq(52L),
                any(String.class), isNull(), eq("INVALID_ENVELOPE"),
                any(String.class), isNull()
        );
        verify(messages, never()).recordFailure(
                any(), any(), any(), any(), anyInt(), anyLong(), any(), any(), any(), anyBoolean()
        );
        verify(acknowledgment).acknowledge();
    }
}
