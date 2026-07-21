package com.ledgerops.simulator;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;

final class SimulatorWebhookSender implements AutoCloseable {
    private final URI coreBaseUrl;
    private final SimulatorWebhookSigner signer;
    private final Clock clock;
    private final HttpClient client;
    private final Tracer tracer;

    SimulatorWebhookSender(URI coreBaseUrl, SimulatorWebhookSigner signer, Clock clock) {
        this(coreBaseUrl, signer, clock, null);
    }

    SimulatorWebhookSender(
            URI coreBaseUrl,
            SimulatorWebhookSigner signer,
            Clock clock,
            Tracer tracer
    ) {
        this.coreBaseUrl = coreBaseUrl;
        this.signer = signer;
        this.clock = clock;
        this.tracer = tracer;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    }

    int send(SimulatorWebhookClaim claim) throws Exception {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Simulator webhook HTTP calls cannot run inside a database transaction");
        }
        Span span = startSpan(claim);
        try (Tracer.SpanInScope ignored = span == null ? null : tracer.withSpan(span)) {
            int status = sendWithinTrace(claim, span);
            if (span != null) span.tag("http.response.status_code", status);
            return status;
        } catch (Exception exception) {
            if (span != null) span.error(exception);
            throw exception;
        } finally {
            if (span != null) span.end();
        }
    }

    private int sendWithinTrace(SimulatorWebhookClaim claim, Span span) throws Exception {
        byte[] body = claim.payload().getBytes(StandardCharsets.UTF_8);
        String timestamp = Long.toString(clock.instant().getEpochSecond());
        String eventId = claim.providerEventId().toString();
        HttpRequest.Builder request = HttpRequest.newBuilder(coreBaseUrl.resolve(
                        "/internal/provider/v1/webhooks"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("X-LedgerOps-Key-Id", signer.keyId())
                .header("X-LedgerOps-Timestamp", timestamp)
                .header("X-LedgerOps-Event-Id", eventId)
                .header("X-LedgerOps-Signature", "INVALID".equals(claim.signatureMode())
                        ? "v1=invalid"
                        : signer.sign(timestamp, eventId, body));
        String traceparent = outboundTraceparent(span, claim.traceparent());
        if (traceparent != null) request.header("traceparent", traceparent);
        if (claim.tracestate() != null) request.header("tracestate", claim.tracestate());
        return client.send(request.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private Span startSpan(SimulatorWebhookClaim claim) {
        if (tracer == null) return null;
        Span.Builder builder = tracer.spanBuilder()
                .name("simulator.webhook.http")
                .kind(Span.Kind.CLIENT)
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
            builder.setNoParent();
        }
        return builder.start();
    }

    private String outboundTraceparent(Span span, String fallback) {
        if (span == null) return fallback;
        var context = span.context();
        return "00-" + context.traceId() + "-" + context.spanId() + "-"
                + (context.sampled() ? "01" : "00");
    }

    @Override
    public void close() {
        client.close();
    }
}
