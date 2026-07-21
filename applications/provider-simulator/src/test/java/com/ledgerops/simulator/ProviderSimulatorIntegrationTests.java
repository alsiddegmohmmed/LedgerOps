package com.ledgerops.simulator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ProviderSimulatorIntegrationTests.PostgresConfiguration.class)
class ProviderSimulatorIntegrationTests {
    private static final String SECRET = "test-only-core-to-simulator-secret";
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;

    @Test
    void signedSubmissionCreatesOneDurableProviderTransactionAndEquivalentReplayReturnsIt()
            throws Exception {
        UUID paymentId = UUID.randomUUID();
        String key = "payment:" + paymentId;
        String body = submission(paymentId, key, "a".repeat(64));

        HttpResponse<String> first = send("/provider/v1/payments", body, true);
        HttpResponse<String> replay = send("/provider/v1/payments", body, true);

        assertEquals(200, first.statusCode());
        assertEquals(200, replay.statusCode());
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM simulator.provider_transactions
                 WHERE provider_client_id = 'ledgerops-core-test'
                   AND provider_idempotency_key = ?
                """, Integer.class, key));
        assertTrue(replay.body().contains("\"category\":\"SUCCESS\""));
    }

    @Test
    void changedContentUnderProviderIdempotencyKeyIsRejectedWithoutReplacement()
            throws Exception {
        UUID paymentId = UUID.randomUUID();
        String key = "payment:" + paymentId;
        assertEquals(200, send("/provider/v1/payments",
                submission(paymentId, key, "b".repeat(64)), true).statusCode());
        assertEquals(409, send("/provider/v1/payments",
                submission(paymentId, key, "c".repeat(64)), true).statusCode());
        assertEquals("b".repeat(64), jdbc.queryForObject("""
                SELECT request_intent_hash FROM simulator.provider_transactions
                 WHERE provider_idempotency_key = ?
                """, String.class, key));
    }

    @Test
    void statusQueryFindsAcceptedTransactionAndInvalidSignatureIsUnauthorized()
            throws Exception {
        UUID paymentId = UUID.randomUUID();
        String key = "payment:" + paymentId;
        send("/provider/v1/payments", submission(paymentId, key, "d".repeat(64)), true);

        HttpResponse<String> status = send("/provider/v1/payment-status-queries",
                "{\"providerIdempotencyKey\":\"" + key + "\"}", true);
        HttpResponse<String> invalid = send("/provider/v1/payment-status-queries",
                "{\"providerIdempotencyKey\":\"" + key + "\"}", false);

        assertEquals(200, status.statusCode());
        assertTrue(status.body().contains("\"found\":true"));
        assertEquals(401, invalid.statusCode());
    }

    @Test
    void timeoutAfterDurableAcceptanceIsRecoverableByIdempotencyAndStatusQuery()
            throws Exception {
        UUID paymentId = UUID.randomUUID();
        String key = "payment:" + paymentId;
        jdbc.update("""
                INSERT INTO simulator.scenario_overrides
                    (provider_client_id, provider_idempotency_key, scenario)
                VALUES ('ledgerops-core-test', ?, 'TIMEOUT_THEN_SUCCESS')
                """, key);
        String body = submission(paymentId, key, "e".repeat(64));
        assertThrows(java.net.http.HttpTimeoutException.class,
                () -> sendWithTimeout("/provider/v1/payments", body, Duration.ofSeconds(1)));

        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM simulator.provider_transactions
                 WHERE provider_client_id = 'ledgerops-core-test'
                   AND provider_idempotency_key = ?
                """, Integer.class, key));
        HttpResponse<String> recovered = send("/provider/v1/payment-status-queries",
                "{\"providerIdempotencyKey\":\"" + key + "\"}", true);
        assertEquals(200, recovered.statusCode());
        assertTrue(recovered.body().contains("\"category\":\"SUCCESS\""));
    }

    @Test
    void deterministicConfiguredDeclineAndTemporaryFailurePreserveAcceptanceSemantics()
            throws Exception {
        UUID declinedPayment = UUID.randomUUID();
        String declinedKey = "payment:" + declinedPayment;
        configure(declinedKey, "DECLINE");
        HttpResponse<String> declined = send("/provider/v1/payments",
                submission(declinedPayment, declinedKey, "f".repeat(64)), true);
        assertEquals(200, declined.statusCode());
        assertTrue(declined.body().contains("\"category\":\"DECLINED\""));

        UUID temporaryPayment = UUID.randomUUID();
        String temporaryKey = "payment:" + temporaryPayment;
        configure(temporaryKey, "TEMPORARY_FAILURE");
        HttpResponse<String> temporary = send("/provider/v1/payments",
                submission(temporaryPayment, temporaryKey, "1".repeat(64)), true);
        assertEquals(503, temporary.statusCode());
        assertTrue(temporary.body().contains("\"accepted\":false"));
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM simulator.provider_transactions
                 WHERE provider_idempotency_key = ?
                """, Integer.class, temporaryKey));
    }

    private HttpResponse<String> send(String path, String body, boolean valid) throws Exception {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String requestId = UUID.randomUUID().toString();
        String signature = valid ? sign(path, timestamp, requestId, body) : "v1=invalid";
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-LedgerOps-Key-Id", "core-test-key")
                .header("X-LedgerOps-Timestamp", timestamp)
                .header("X-LedgerOps-Request-Id", requestId)
                .header("X-LedgerOps-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return HttpClient.newHttpClient().send(request,
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendWithTimeout(String path, String body, Duration timeout)
            throws Exception {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String requestId = UUID.randomUUID().toString();
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("X-LedgerOps-Key-Id", "core-test-key")
                .header("X-LedgerOps-Timestamp", timestamp)
                .header("X-LedgerOps-Request-Id", requestId)
                .header("X-LedgerOps-Signature", sign(path, timestamp, requestId, body))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String sign(String path, String timestamp, String requestId, String body)
            throws Exception {
        String bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(body.getBytes(StandardCharsets.UTF_8)));
        String canonical = String.join("\n", "v1", "POST", path, "core-test-key",
                timestamp, requestId, bodyHash);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "v1=" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private String submission(UUID paymentId, String key, String hash) {
        return "{" +
                "\"attemptId\":\"" + UUID.randomUUID() + "\"," +
                "\"paymentId\":\"" + paymentId + "\"," +
                "\"attemptSequence\":1," +
                "\"providerId\":\"SIMULATOR\"," +
                "\"providerIdempotencyKey\":\"" + key + "\"," +
                "\"amount\":\"10.00\"," +
                "\"currency\":\"SAR\"," +
                "\"paymentMethodCategory\":\"CARD\"," +
                "\"requestIntentHash\":\"" + hash + "\"}";
    }

    private void configure(String key, String scenario) {
        jdbc.update("""
                INSERT INTO simulator.scenario_overrides
                    (provider_client_id, provider_idempotency_key, scenario)
                VALUES ('ledgerops-core-test', ?, ?)
                """, key, scenario);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PostgresConfiguration {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:17-alpine");
        }
    }
}
