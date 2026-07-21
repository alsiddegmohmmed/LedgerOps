package com.ledgerops.payment.infrastructure;

import com.ledgerops.messaging.api.ConsumerFailureResult;
import com.ledgerops.messaging.api.ConsumerMessageStore;
import com.ledgerops.messaging.api.IncomingMessage;
import com.ledgerops.payment.application.ApplyProviderResult;
import com.ledgerops.payment.application.PaymentProviderResultConsistencyException;
import com.ledgerops.payment.application.PaymentProviderResultOutcome;
import com.ledgerops.payment.application.PaymentProviderResultResult;
import com.ledgerops.payment.application.ProviderEvidenceUnavailableException;
import com.ledgerops.payment.domain.PaymentStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Duration;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderResultConsumerTests {

    @Test
    void validResultIsAppliedAndAcknowledged() {
        ConsumerMessageStore messages = mock(ConsumerMessageStore.class);
        ApplyProviderResult application = mock(ApplyProviderResult.class);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        UUID paymentId = UUID.randomUUID();
        String raw = envelope(UUID.randomUUID(), UUID.randomUUID(), paymentId, 1);
        when(application.apply(any(), any())).thenReturn(new PaymentProviderResultResult(
                paymentId, PaymentStatus.COMPLETED,
                PaymentProviderResultOutcome.COMPLETED, UUID.randomUUID(), UUID.randomUUID()
        ));
        ProviderResultConsumer consumer = consumer(messages, application);

        consumer.receive(record(paymentId, raw), acknowledgment);

        verify(application).apply(any(IncomingMessage.class), any());
        verify(acknowledgment).acknowledge();
        verify(messages, never()).recordFailure(
                any(), any(), any(), any(), anyInt(), anyLong(), any(), any(), any(), anyBoolean()
        );
    }

    @Test
    void permanentConsistencyFailureDeadLettersImmediately() {
        ConsumerMessageStore messages = mock(ConsumerMessageStore.class);
        ApplyProviderResult application = mock(ApplyProviderResult.class);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        UUID paymentId = UUID.randomUUID();
        String raw = envelope(UUID.randomUUID(), UUID.randomUUID(), paymentId, 1);
        when(application.apply(any(), any())).thenThrow(
                new PaymentProviderResultConsistencyException(paymentId, "conflict")
        );
        when(messages.recordFailure(
                any(), eq(raw), any(), eq("ledgerops.provider.results.v1"),
                eq(2), eq(41L), eq("RESULT_CONSISTENCY_FAILURE"),
                eq("RESULT_CONSISTENCY_FAILURE"), any(), eq(true)
        )).thenReturn(new ConsumerFailureResult(5, true));
        ProviderResultConsumer consumer = consumer(messages, application);

        consumer.receive(record(paymentId, raw), acknowledgment);

        verify(messages).recordFailure(
                any(), eq(raw), any(), eq("ledgerops.provider.results.v1"),
                eq(2), eq(41L), eq("RESULT_CONSISTENCY_FAILURE"),
                eq("RESULT_CONSISTENCY_FAILURE"), any(), eq(true)
        );
        verify(acknowledgment).acknowledge();
    }

    @Test
    void temporarilyMissingEvidenceUsesBoundedFailureCounting() {
        ConsumerMessageStore messages = mock(ConsumerMessageStore.class);
        ApplyProviderResult application = mock(ApplyProviderResult.class);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        UUID paymentId = UUID.randomUUID();
        String raw = envelope(UUID.randomUUID(), UUID.randomUUID(), paymentId, 1);
        when(application.apply(any(), any())).thenThrow(
                new ProviderEvidenceUnavailableException(UUID.randomUUID())
        );
        when(messages.recordFailure(
                any(), eq(raw), any(), eq("ledgerops.provider.results.v1"),
                eq(2), eq(41L), eq("BUSINESS_PROCESSING_FAILURE"),
                eq("BUSINESS_PROCESSING_FAILURE"), any(), eq(false)
        )).thenReturn(new ConsumerFailureResult(1, false));
        ProviderResultConsumer consumer = consumer(messages, application);

        consumer.receive(record(paymentId, raw), acknowledgment);

        verify(messages).recordFailure(
                any(), eq(raw), any(), eq("ledgerops.provider.results.v1"),
                eq(2), eq(41L), eq("BUSINESS_PROCESSING_FAILURE"),
                eq("BUSINESS_PROCESSING_FAILURE"), any(), eq(false)
        );
        verify(acknowledgment).nack(Duration.ofSeconds(1));
    }

    @Test
    void transportInvalidRecordBypassesInboxIdentity() {
        ConsumerMessageStore messages = mock(ConsumerMessageStore.class);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        ProviderResultConsumer consumer = consumer(
                messages,
                mock(ApplyProviderResult.class)
        );

        consumer.receive(new ConsumerRecord<>(
                "ledgerops.provider.results.v1", 3, 52, "key", "not-json"
        ), acknowledgment);

        verify(messages).recordTransportDeadLetter(
                eq("payment-provider-result-consumer-v1"),
                eq("ledgerops.provider.results.v1"), eq(3), eq(52L),
                any(), isNull(), eq("INVALID_ENVELOPE"), any(), isNull()
        );
        verify(messages, never()).recordFailure(
                any(), any(), any(), any(), anyInt(), anyLong(), any(), any(), any(), anyBoolean()
        );
        verify(acknowledgment).acknowledge();
    }

    @Test
    void unsupportedVersionUsesNormalDeadInboxIdentity() {
        ConsumerMessageStore messages = mock(ConsumerMessageStore.class);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        UUID paymentId = UUID.randomUUID();
        String raw = envelope(UUID.randomUUID(), UUID.randomUUID(), paymentId, 2);
        when(messages.recordFailure(
                any(), eq(raw), any(), eq("ledgerops.provider.results.v1"),
                eq(2), eq(41L), eq("UNSUPPORTED_SCHEMA_VERSION"),
                eq("UNSUPPORTED_SCHEMA_VERSION"), any(), eq(true)
        )).thenReturn(new ConsumerFailureResult(5, true));
        ProviderResultConsumer consumer = consumer(
                messages,
                mock(ApplyProviderResult.class)
        );

        consumer.receive(record(paymentId, raw), acknowledgment);

        verify(messages).recordFailure(
                any(), eq(raw), any(), eq("ledgerops.provider.results.v1"),
                eq(2), eq(41L), eq("UNSUPPORTED_SCHEMA_VERSION"),
                eq("UNSUPPORTED_SCHEMA_VERSION"), any(), eq(true)
        );
        verify(acknowledgment).acknowledge();
    }

    private ProviderResultConsumer consumer(
            ConsumerMessageStore messages,
            ApplyProviderResult application
    ) {
        return new ProviderResultConsumer(
                messages,
                application,
                new SimpleMeterRegistry()
        );
    }

    private ConsumerRecord<String, String> record(UUID paymentId, String raw) {
        return new ConsumerRecord<>(
                "ledgerops.provider.results.v1", 2, 41, paymentId.toString(), raw
        );
    }

    private String envelope(
            UUID messageId,
            UUID tenantId,
            UUID paymentId,
            int schemaVersion
    ) {
        UUID attemptId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        UUID resultId = UUID.randomUUID();
        return "{" +
                "\"messageId\":\"" + messageId + "\"," +
                "\"messageType\":\"ProviderResultObserved\"," +
                "\"schemaVersion\":" + schemaVersion + "," +
                "\"aggregateId\":\"" + paymentId + "\"," +
                "\"tenantId\":\"" + tenantId + "\"," +
                "\"correlationId\":\"" + UUID.randomUUID() + "\"," +
                "\"causationId\":\"" + UUID.randomUUID() + "\"," +
                "\"occurredAt\":\"2026-07-21T12:00:00Z\"," +
                "\"payload\":{" +
                "\"attemptId\":\"" + attemptId + "\"," +
                "\"evidenceOrigin\":\"SUBMISSION_RESPONSE\"," +
                "\"observedAt\":\"2026-07-21T12:00:00Z\"," +
                "\"paymentId\":\"" + paymentId + "\"," +
                "\"providerEvidenceId\":\"" + evidenceId + "\"," +
                "\"providerId\":\"SIMULATOR\"," +
                "\"providerIdempotencyKey\":\"payment:" + paymentId + "\"," +
                "\"providerReference\":\"simulator-reference\"," +
                "\"providerResultId\":\"" + resultId + "\"," +
                "\"providerResultCategory\":\"SUCCESS\"," +
                "\"retryDisposition\":\"NOT_RETRYABLE\"}}";
    }
}
