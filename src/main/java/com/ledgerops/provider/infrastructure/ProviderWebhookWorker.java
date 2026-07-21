package com.ledgerops.provider.infrastructure;

import com.ledgerops.provider.application.ProviderWebhookClaim;
import com.ledgerops.provider.application.ProviderWebhookExecutionStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "ledgerops.provider.webhook.processing.enabled",
        havingValue = "true"
)
class ProviderWebhookWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProviderWebhookWorker.class);

    private final ProviderWebhookExecutionStore store;
    private final MeterRegistry meters;
    private final String leaseOwner;
    private final Tracer tracer;

    @Autowired
    ProviderWebhookWorker(
            ProviderWebhookExecutionStore store,
            MeterRegistry meters,
            @Value("${ledgerops.provider.webhook.processing.owner:${HOSTNAME:local}}")
            String leaseOwner,
            ObjectProvider<Tracer> tracer
    ) {
        this(store, meters, leaseOwner, tracer.getIfAvailable());
    }

    ProviderWebhookWorker(
            ProviderWebhookExecutionStore store,
            MeterRegistry meters,
            String leaseOwner,
            Tracer tracer
    ) {
        this.store = store;
        this.meters = meters;
        this.leaseOwner = leaseOwner;
        this.tracer = tracer;
    }

    @Scheduled(fixedDelayString = "${ledgerops.provider.webhook.processing.delay-ms:250}")
    void processOne() {
        store.claimNextWebhook(leaseOwner).ifPresent(this::process);
    }

    private void process(ProviderWebhookClaim claim) {
        Span span = startSpan(claim);
        try (Tracer.SpanInScope ignored = span == null ? null : tracer.withSpan(span)) {
            ProviderWebhookClaim traced = withCurrentTrace(claim);
            var outcome = store.processWebhook(traced);
            meters.counter("ledgerops.provider.webhook.processing",
                    "outcome", outcome.name().toLowerCase(java.util.Locale.ROOT)).increment();
            LOGGER.info(
                    "Provider webhook processed tenantId={} paymentId={} attemptId={} providerEventId={} outcome={} correlationId={}",
                    claim.tenantId(), claim.paymentId(), claim.attemptId(),
                    claim.providerEventId(), outcome, claim.correlationId());
        } catch (RuntimeException exception) {
            if (span != null) span.error(exception);
            throw exception;
        } finally {
            if (span != null) span.end();
        }
    }

    private Span startSpan(ProviderWebhookClaim claim) {
        if (tracer == null) return null;
        Span.Builder builder = tracer.spanBuilder()
                .name("provider.webhook.process")
                .kind(Span.Kind.CONSUMER)
                .tag("provider.id", "SIMULATOR");
        String value = claim.traceparent();
        if (value != null && value.matches(
                "00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")) {
            String[] parts = value.split("-");
            builder.setParent(tracer.traceContextBuilder()
                    .traceId(parts[1])
                    .spanId(parts[2])
                    .sampled((Integer.parseInt(parts[3], 16) & 1) == 1)
                    .build());
        } else {
            if (value != null || claim.tracestate() != null) {
                meters.counter("ledgerops.trace.context.invalid",
                        "boundary", "provider_webhook_processor").increment();
            }
            builder.setNoParent();
        }
        return builder.start();
    }

    private ProviderWebhookClaim withCurrentTrace(ProviderWebhookClaim claim) {
        if (tracer == null || tracer.currentSpan() == null) return claim;
        var context = tracer.currentSpan().context();
        String traceparent = "00-" + context.traceId() + "-" + context.spanId() + "-"
                + (context.sampled() ? "01" : "00");
        return new ProviderWebhookClaim(
                claim.eventId(), claim.tenantId(), claim.paymentId(), claim.attemptId(),
                claim.attemptSequence(), claim.providerId(), claim.providerIdempotencyKey(),
                claim.providerEventId(), claim.providerResultId(), claim.providerReference(),
                claim.category(), claim.providerOccurredAt(), claim.payloadHash(),
                claim.correlationId(), traceparent, claim.tracestate(), claim.receivedAt(),
                claim.leaseToken(), claim.leaseExpiresAt()
        );
    }
}
