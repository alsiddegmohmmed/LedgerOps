package com.ledgerops.provider.infrastructure;

import com.ledgerops.provider.application.ProviderWorkClaim;
import com.ledgerops.provider.application.ProviderWorkType;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.URI;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.nio.file.Path;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderHttpBoundaryTests {
    @Test
    void canonicalSignatureUsesExactLfDelimitedUnsignedTraceFreeContract() {
        var fixture = readFixture();
        ProviderHmacSigner signer = new ProviderHmacSigner(fixture.keyId(), fixture.secret());
        String signature = signer.sign(fixture.method(), fixture.path(), fixture.timestamp(),
                fixture.requestId(), fixture.body().getBytes());

        assertEquals(fixture.signature(), signature);
    }

    @Test
    void providerGatewayRejectsCallsInsideDatabaseTransactionBeforeNetworkAccess() {
        SimulatorProviderGateway gateway = new SimulatorProviderGateway(
                URI.create("http://127.0.0.1:1"),
                new ProviderHmacSigner("core-key", "test-only-shared-secret"),
                CircuitBreaker.ofDefaults("test"),
                Bulkhead.of("test", BulkheadConfig.custom().maxConcurrentCalls(10).build()),
                Clock.systemUTC());
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(IllegalStateException.class, () -> gateway.execute(claim()));
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    void temporaryFailureIsSafeOnlyWhenResponseExplicitlyProvesNoAcceptance() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(0), 0);
        var response = new java.util.concurrent.atomic.AtomicReference<>(
                "{\"category\":\"TEMPORARY_FAILURE\",\"accepted\":false}");
        server.createContext("/provider/v1/payments", exchange -> {
            byte[] bytes = response.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try (SimulatorProviderGateway gateway = gateway(server.getAddress().getPort())) {
            var safe = gateway.execute(claim());
            assertEquals(com.ledgerops.provider.api.RetryDisposition.SAFE_TO_RESUBMIT,
                    safe.disposition());
            assertTrue(safe.noAcceptanceProven());

            response.set("{\"category\":\"TEMPORARY_FAILURE\"}");
            var ambiguous = gateway.execute(claim());
            assertEquals(com.ledgerops.provider.api.RetryDisposition.STATUS_RECOVERY_REQUIRED,
                    ambiguous.disposition());
            assertFalse(ambiguous.noAcceptanceProven());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void transmittedReadTimeoutBecomesUnknownAndNeverSafeToResubmit() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/provider/v1/payments", exchange -> {
            try {
                Thread.sleep(6_000);
                byte[] bytes = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (Exception ignored) {
                // The client is expected to close after the bounded timeout.
            } finally {
                exchange.close();
            }
        });
        server.start();
        try (SimulatorProviderGateway gateway = gateway(server.getAddress().getPort())) {
            long started = System.nanoTime();
            var result = gateway.execute(claim());
            long elapsedMillis = java.time.Duration.ofNanos(
                    System.nanoTime() - started).toMillis();
            assertEquals(com.ledgerops.provider.api.ProviderResultCategory.UNKNOWN,
                    result.category());
            assertEquals(com.ledgerops.provider.api.RetryDisposition.STATUS_RECOVERY_REQUIRED,
                    result.disposition());
            assertFalse(result.noAcceptanceProven());
            assertEquals("TIMEOUT", result.communicationOutcome());
            assertTrue(elapsedMillis < 5_500, "total Provider timeout must not exceed 5 seconds");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void resilienceConfigurationUsesEveryApprovedReleaseValue() {
        ProviderExecutionConfiguration configuration = new ProviderExecutionConfiguration();
        var circuit = configuration.providerCircuitBreaker().getCircuitBreakerConfig();
        var bulkhead = configuration.providerBulkhead().getBulkheadConfig();

        assertEquals(20, circuit.getSlidingWindowSize());
        assertEquals(10, circuit.getMinimumNumberOfCalls());
        assertEquals(50.0f, circuit.getFailureRateThreshold());
        assertEquals(50.0f, circuit.getSlowCallRateThreshold());
        assertEquals(java.time.Duration.ofSeconds(3), circuit.getSlowCallDurationThreshold());
        assertEquals(3, circuit.getPermittedNumberOfCallsInHalfOpenState());
        assertEquals(30_000L, circuit.getWaitIntervalFunctionInOpenState().apply(1));
        assertEquals(10, bulkhead.getMaxConcurrentCalls());
        assertEquals(java.time.Duration.ZERO, bulkhead.getMaxWaitDuration());
    }

    private SimulatorProviderGateway gateway(int port) {
        return new SimulatorProviderGateway(
                URI.create("http://127.0.0.1:" + port),
                new ProviderHmacSigner("core-key", "test-only-shared-secret"),
                CircuitBreaker.ofDefaults("test-" + UUID.randomUUID()),
                Bulkhead.of("test-" + UUID.randomUUID(),
                        BulkheadConfig.custom().maxConcurrentCalls(10).build()),
                Clock.systemUTC());
    }

    private ProviderWorkClaim claim() {
        UUID paymentId = UUID.randomUUID();
        return new ProviderWorkClaim(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                paymentId, ProviderWorkType.SUBMISSION, "SIMULATOR", "payment:" + paymentId,
                "a".repeat(64), "{}", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Instant.now().plusSeconds(30), false, false);
    }

    private HmacFixture readFixture() {
        try {
            var value = JsonMapper.builder().build().readTree(Path.of(
                    "packages/provider-contracts/v1/fixtures/hmac-core-to-simulator.json").toFile());
            return new HmacFixture(value.required("method").asString(),
                    value.required("rawPath").asString(), value.required("keyId").asString(),
                    value.required("timestamp").asString(), value.required("requestId").asString(),
                    value.required("rawBody").asString(), value.required("testSecret").asString(),
                    value.required("signature").asString());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record HmacFixture(String method, String path, String keyId, String timestamp,
                               String requestId, String body, String secret, String signature) {
    }
}
