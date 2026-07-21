package com.ledgerops.provider.infrastructure;

import com.ledgerops.messaging.api.ConsumerFailureResult;
import com.ledgerops.messaging.api.ConsumerMessageStore;
import com.ledgerops.messaging.api.IncomingMessage;
import com.ledgerops.provider.application.AcceptProviderSubmissionCommand;
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

class ProviderCommandConsumerTests {

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
