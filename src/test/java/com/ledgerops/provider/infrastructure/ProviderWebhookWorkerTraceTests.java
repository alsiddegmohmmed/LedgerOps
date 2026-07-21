package com.ledgerops.provider.infrastructure;

import com.ledgerops.provider.api.ProviderResultCategory;
import com.ledgerops.provider.application.ProviderWebhookClaim;
import com.ledgerops.provider.application.ProviderWebhookExecutionStore;
import com.ledgerops.provider.application.ProviderWebhookProcessingOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderWebhookWorkerTraceTests {

    @Test
    void asynchronousWebhookProcessingContinuesPersistedTraceAndEmitsChildContext() {
        var exporter = InMemorySpanExporter.create();
        var provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
        var telemetry = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        var tracer = new OtelTracer(
                telemetry.getTracer("ledgerops-test"), new OtelCurrentTraceContext(), ignored -> {
                }
        );
        ProviderWebhookExecutionStore store = mock(ProviderWebhookExecutionStore.class);
        ProviderWebhookClaim claim = claim();
        when(store.claimNextWebhook("test-worker")).thenReturn(Optional.of(claim));
        when(store.processWebhook(org.mockito.ArgumentMatchers.any()))
                .thenReturn(ProviderWebhookProcessingOutcome.COMPLETED);
        var worker = new ProviderWebhookWorker(
                store, new SimpleMeterRegistry(), "test-worker", tracer
        );

        worker.processOne();

        var span = exporter.getFinishedSpanItems().stream()
                .filter(item -> item.getName().equals("provider.webhook.process"))
                .findFirst().orElseThrow();
        assertEquals("00000000000000000000000000000001", span.getTraceId());
        assertEquals("0000000000000002", span.getParentSpanId());
        assertEquals(SpanKind.CONSUMER, span.getKind());
        ArgumentCaptor<ProviderWebhookClaim> processed =
                ArgumentCaptor.forClass(ProviderWebhookClaim.class);
        verify(store).processWebhook(processed.capture());
        assertEquals("00-" + span.getTraceId() + "-" + span.getSpanId() + "-01",
                processed.getValue().traceparent());
        provider.close();
    }

    private ProviderWebhookClaim claim() {
        UUID paymentId = UUID.randomUUID();
        return new ProviderWebhookClaim(
                UUID.randomUUID(), UUID.randomUUID(), paymentId, UUID.randomUUID(), 1,
                "SIMULATOR", "payment:" + paymentId, UUID.randomUUID(), UUID.randomUUID(),
                "SIM-ref", ProviderResultCategory.SUCCESS, Instant.now(), "a".repeat(64),
                UUID.randomUUID(),
                "00-00000000000000000000000000000001-0000000000000002-01",
                null, Instant.now(), UUID.randomUUID(), Instant.now().plusSeconds(30)
        );
    }
}
