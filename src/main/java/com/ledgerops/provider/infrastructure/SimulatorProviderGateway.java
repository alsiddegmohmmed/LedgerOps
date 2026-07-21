package com.ledgerops.provider.infrastructure;

import com.ledgerops.provider.api.ProviderResultCategory;
import com.ledgerops.provider.api.RetryDisposition;
import com.ledgerops.provider.application.ProviderCallResult;
import com.ledgerops.provider.application.ProviderCallDeferredException;
import com.ledgerops.provider.application.ProviderGateway;
import com.ledgerops.provider.application.ProviderWorkClaim;
import com.ledgerops.provider.application.ProviderWorkType;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@ConditionalOnProperty(name = "ledgerops.provider.execution.enabled", havingValue = "true")
final class SimulatorProviderGateway implements ProviderGateway, AutoCloseable {
    private static final String SUBMISSION_PATH = "/provider/v1/payments";
    private static final String STATUS_PATH = "/provider/v1/payment-status-queries";
    private final URI baseUri;
    private final CloseableHttpClient http;
    private final ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();
    private final ProviderHmacSigner signer;
    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;
    private final Clock clock;
    private final Tracer tracer;
    private final JsonMapper json = JsonMapper.builder().build();

    SimulatorProviderGateway(URI baseUri, ProviderHmacSigner signer,
            CircuitBreaker circuitBreaker, Bulkhead bulkhead, Clock clock) {
        this(baseUri, signer, circuitBreaker, bulkhead, clock, null);
    }

    SimulatorProviderGateway(URI baseUri, ProviderHmacSigner signer,
            CircuitBreaker circuitBreaker, Bulkhead bulkhead, Clock clock, Tracer tracer) {
        this.baseUri = baseUri;
        this.signer = signer;
        this.circuitBreaker = circuitBreaker;
        this.bulkhead = bulkhead;
        this.clock = clock;
        this.tracer = tracer;
        RequestConfig timeouts = RequestConfig.custom()
                .setResponseTimeout(Timeout.ofSeconds(3))
                .build();
        ConnectionConfig connection = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(1))
                .setSocketTimeout(Timeout.ofSeconds(3))
                .build();
        var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(10).setMaxConnPerRoute(10)
                .setDefaultConnectionConfig(connection).build();
        this.http = HttpClients.custom().setDefaultRequestConfig(timeouts)
                .setConnectionManager(connectionManager)
                .build();
    }

    @Override
    public ProviderCallResult execute(ProviderWorkClaim claim) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Provider HTTP must execute outside a transaction");
        }
        Span span = startHttpSpan(claim);
        try (Tracer.SpanInScope ignored = span == null ? null : tracer.withSpan(span)) {
            ProviderCallResult result = executeWithinTrace(claim, span);
            if (span != null) {
                span.tag("provider.result.category", result.category().name());
                span.tag("provider.retry.disposition", result.disposition().name());
            }
            return result;
        } catch (RuntimeException exception) {
            if (span != null) span.error(exception);
            throw exception;
        } finally {
            if (span != null) span.end();
        }
    }

    private ProviderCallResult executeWithinTrace(ProviderWorkClaim claim, Span span) {
        UUID requestId = UUID.randomUUID();
        String path = claim.workType() == ProviderWorkType.SUBMISSION
                ? SUBMISSION_PATH : STATUS_PATH;
        byte[] body = body(claim).getBytes(StandardCharsets.UTF_8);
        Instant started = clock.instant();
        Supplier<GatewayResponse> call = () -> {
            GatewayResponse response = send(path, requestId, body,
                    outboundTraceparent(span, claim.traceparent()), claim.tracestate());
            if (response.statusCode() >= 500) {
                throw new ProviderHttpFailureException(response);
            }
            return response;
        };
        try {
            GatewayResponse response = Bulkhead.decorateSupplier(
                    bulkhead, CircuitBreaker.decorateSupplier(circuitBreaker, call)).get();
            return interpret(claim, requestId, body, response, started, clock.instant());
        } catch (CallNotPermittedException exception) {
            throw new ProviderCallDeferredException("CIRCUIT_OPEN");
        } catch (io.github.resilience4j.bulkhead.BulkheadFullException exception) {
            throw new ProviderCallDeferredException("BULKHEAD_FULL");
        } catch (ProviderHttpFailureException exception) {
            return interpret(claim, requestId, body, exception.response(),
                    started, clock.instant());
        } catch (RuntimeException exception) {
            Throwable cause = rootCause(exception);
            if (claim.preTransmissionRetryAvailable() && isProvenPreTransmission(cause)) {
                throw new ProviderCallDeferredException("PRETRANSMISSION_FAILURE");
            }
            String code = cause instanceof java.util.concurrent.TimeoutException
                    || cause instanceof java.net.SocketTimeoutException
                    ? "PROVIDER_TIMEOUT" : "PROVIDER_CONNECTION_FAILURE";
            return failure(requestId, body, started,
                    "PROVIDER_TIMEOUT".equals(code) ? "TIMEOUT" : "CONNECTION_FAILURE",
                    code, false);
        }
    }

    private Span startHttpSpan(ProviderWorkClaim claim) {
        if (tracer == null) return null;
        Span.Builder builder = tracer.spanBuilder()
                .name("provider.simulator.http")
                .kind(Span.Kind.CLIENT)
                .tag("provider.id", "SIMULATOR")
                .tag("provider.work.type", claim.workType().name());
        String traceparent = claim.traceparent();
        if (traceparent != null && traceparent.matches(
                "00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")) {
            String[] parts = traceparent.split("-");
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

    private boolean isProvenPreTransmission(Throwable cause) {
        return cause instanceof java.net.ConnectException
                || cause instanceof java.net.UnknownHostException
                || cause instanceof javax.net.ssl.SSLHandshakeException;
    }

    private GatewayResponse send(
            String path,
            UUID requestId,
            byte[] body,
            String traceparent,
            String tracestate
    ) {
        String timestamp = Long.toString(clock.instant().getEpochSecond());
        HttpPost request = new HttpPost(baseUri.resolve(path));
        request.setHeader("Content-Type", "application/json");
        request.setHeader("X-LedgerOps-Key-Id", signer.keyId());
        request.setHeader("X-LedgerOps-Timestamp", timestamp);
        request.setHeader("X-LedgerOps-Request-Id", requestId.toString());
        request.setHeader("X-LedgerOps-Signature",
                signer.sign("POST", path, timestamp, requestId.toString(), body));
        if (traceparent != null) request.setHeader("traceparent", traceparent);
        if (tracestate != null) request.setHeader("tracestate", tracestate);
        request.setEntity(new ByteArrayEntity(body, ContentType.APPLICATION_JSON));
        try {
            return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return http.execute(request, response -> new GatewayResponse(
                            response.getCode(), response.getEntity() == null ? ""
                                    : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8)));
                } catch (Exception exception) {
                    throw new CompletionException(exception);
                }
            }, calls).orTimeout(5, TimeUnit.SECONDS).join();
        } catch (CompletionException exception) {
            throw exception;
        }
    }

    private ProviderCallResult interpret(ProviderWorkClaim claim, UUID requestId, byte[] request,
            GatewayResponse response, Instant started, Instant completed) {
        byte[] responseBytes = response.body().getBytes(StandardCharsets.UTF_8);
        long latency = Math.max(0, Duration.between(started, completed).toMillis());
        if (response.statusCode() == 503 && claim.workType() == ProviderWorkType.SUBMISSION) {
            boolean noAcceptance = false;
            try {
                noAcceptance = json.readTree(response.body()).path("accepted").isBoolean()
                        && !json.readTree(response.body()).path("accepted").asBoolean();
            } catch (Exception ignored) {
                // An unparseable failure response cannot prove non-acceptance.
            }
            return new ProviderCallResult(requestId, UUID.randomUUID(), null,
                    ProviderResultCategory.TEMPORARY_FAILURE,
                    noAcceptance ? RetryDisposition.SAFE_TO_RESUBMIT
                            : RetryDisposition.STATUS_RECOVERY_REQUIRED,
                    false, noAcceptance,
                    response.statusCode(), ProviderHmacSigner.hash(request),
                    ProviderHmacSigner.hash(responseBytes), "RESPONSE", latency,
                    "PROVIDER_TEMPORARY_FAILURE", started, completed);
        }
        if (response.statusCode() == 404 && claim.workType() == ProviderWorkType.STATUS_QUERY) {
            return new ProviderCallResult(requestId, UUID.randomUUID(), null,
                    ProviderResultCategory.TEMPORARY_FAILURE,
                    RetryDisposition.SAFE_TO_RESUBMIT, false, true,
                    response.statusCode(), ProviderHmacSigner.hash(request),
                    ProviderHmacSigner.hash(responseBytes), "RESPONSE", latency,
                    "PROVIDER_TRANSACTION_NOT_FOUND", started, completed);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return new ProviderCallResult(requestId, UUID.randomUUID(), null,
                    ProviderResultCategory.UNKNOWN,
                    RetryDisposition.STATUS_RECOVERY_REQUIRED, false, false,
                    response.statusCode(), ProviderHmacSigner.hash(request),
                    ProviderHmacSigner.hash(responseBytes), "RESPONSE", latency,
                    "UNEXPECTED_PROVIDER_RESPONSE", started, completed);
        }
        try {
            JsonNode value = json.readTree(response.body());
            ProviderResultCategory category = ProviderResultCategory.valueOf(
                    value.required("category").asString());
            UUID providerResultId = UUID.fromString(
                    value.required("providerResultId").asString());
            String reference = value.required("providerReference").asString();
            boolean found = value.path("found").asBoolean(true);
            return new ProviderCallResult(requestId, providerResultId, reference, category,
                    disposition(category, found), found, false, response.statusCode(),
                    ProviderHmacSigner.hash(request), ProviderHmacSigner.hash(responseBytes),
                    "RESPONSE", latency, null, started, completed);
        } catch (Exception exception) {
            return new ProviderCallResult(requestId, UUID.randomUUID(), null,
                    ProviderResultCategory.UNKNOWN, RetryDisposition.STATUS_RECOVERY_REQUIRED,
                    false, false, response.statusCode(), ProviderHmacSigner.hash(request),
                    ProviderHmacSigner.hash(responseBytes), "RESPONSE", latency,
                    "MALFORMED_PROVIDER_RESPONSE", started, completed);
        }
    }

    private RetryDisposition disposition(ProviderResultCategory category, boolean found) {
        return switch (category) {
            case SUCCESS, DECLINED, PERMANENT_FAILURE -> RetryDisposition.NOT_RETRYABLE;
            case ACCEPTED, PENDING, UNKNOWN -> RetryDisposition.STATUS_RECOVERY_REQUIRED;
            case TEMPORARY_FAILURE -> found
                    ? RetryDisposition.STATUS_RECOVERY_REQUIRED
                    : RetryDisposition.SAFE_TO_RESUBMIT;
        };
    }

    private ProviderCallResult failure(UUID requestId, byte[] request, Instant started,
            String outcome, String code, boolean noAcceptanceProven) {
        Instant completed = clock.instant();
        return new ProviderCallResult(requestId, UUID.randomUUID(), null,
                ProviderResultCategory.UNKNOWN, RetryDisposition.STATUS_RECOVERY_REQUIRED,
                false, noAcceptanceProven, null, ProviderHmacSigner.hash(request), null,
                outcome, Math.max(0, Duration.between(started, completed).toMillis()), code,
                started, completed);
    }

    private String body(ProviderWorkClaim claim) {
        if (claim.workType() == ProviderWorkType.SUBMISSION) return claim.commandPayload();
        return "{\"providerIdempotencyKey\":\"" + claim.providerIdempotencyKey() + "\"}";
    }

    private Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    @Override
    public void close() throws Exception {
        http.close();
        calls.close();
    }

    private record GatewayResponse(int statusCode, String body) {
    }

    private static final class ProviderHttpFailureException extends RuntimeException {
        private final GatewayResponse response;

        private ProviderHttpFailureException(GatewayResponse response) {
            super("Provider returned HTTP " + response.statusCode());
            this.response = response;
        }

        private GatewayResponse response() {
            return response;
        }
    }
}
