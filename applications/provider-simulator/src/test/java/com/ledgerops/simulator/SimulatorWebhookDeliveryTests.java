package com.ledgerops.simulator;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Import(SimulatorWebhookDeliveryTests.PostgresConfiguration.class)
class SimulatorWebhookDeliveryTests {
    private static final String KEY_ID = "simulator-webhook-test-key";
    private static final String SECRET = "test-only-simulator-to-core-secret";

    @Autowired SimulatorWebhookDeliveryStore store;
    @Autowired JdbcTemplate jdbc;

    @Test
    void signedWebhookCallOccursOutsideTransactionAndFencedCompletionIsDurable()
            throws Exception {
        insertProviderTransaction();
        SimulatorWebhookClaim claim = store.claimNext("simulator-worker").orElseThrow();
        AtomicBoolean verified = new AtomicBoolean();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        HmacVerifier verifier = new HmacVerifier(KEY_ID, SECRET, Clock.systemUTC());
        server.createContext("/internal/provider/v1/webhooks", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            verified.set(verifier.verify(
                    exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("X-LedgerOps-Key-Id"),
                    exchange.getRequestHeaders().getFirst("X-LedgerOps-Timestamp"),
                    exchange.getRequestHeaders().getFirst("X-LedgerOps-Event-Id"), body,
                    exchange.getRequestHeaders().getFirst("X-LedgerOps-Signature")));
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        server.start();
        try (SimulatorWebhookSender sender = new SimulatorWebhookSender(
                URI.create("http://localhost:" + server.getAddress().getPort()),
                new SimulatorWebhookSigner(KEY_ID, SECRET), Clock.systemUTC())) {
            int status = sender.send(claim);
            store.record(claim, status, null);
        } finally {
            server.stop(0);
        }

        assertTrue(verified.get());
        assertEquals("DELIVERED", jdbc.queryForObject("""
                SELECT status FROM simulator.webhook_deliveries WHERE delivery_id = ?
                """, String.class, claim.deliveryId()));
        assertEquals(1, jdbc.queryForObject("""
                SELECT attempt_count FROM simulator.webhook_deliveries WHERE delivery_id = ?
                """, Integer.class, claim.deliveryId()));
    }

    @Test
    void expiredLeaseIsReclaimedWithANewTokenAndStaleHolderCannotComplete() {
        insertProviderTransaction();
        SimulatorWebhookClaim abandoned = store.claimNext("crashed-worker").orElseThrow();
        jdbc.update("""
                UPDATE simulator.webhook_deliveries
                   SET lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                 WHERE delivery_id = ?
                """, abandoned.deliveryId());
        SimulatorWebhookClaim reclaimed = store.claimNext("recovery-worker").orElseThrow();

        assertNotEquals(abandoned.leaseToken(), reclaimed.leaseToken());
        assertThrows(IllegalStateException.class,
                () -> store.record(abandoned, 202, null));
        store.record(reclaimed, 202, null);
        assertEquals("DELIVERED", jdbc.queryForObject("""
                SELECT status FROM simulator.webhook_deliveries WHERE delivery_id = ?
                """, String.class, reclaimed.deliveryId()));
    }

    @Test
    void senderRejectsNetworkAccessInsideDatabaseTransaction() throws Exception {
        insertProviderTransaction();
        SimulatorWebhookClaim claim = store.claimNext("transaction-probe").orElseThrow();
        try (SimulatorWebhookSender sender = new SimulatorWebhookSender(
                URI.create("http://127.0.0.1:1"),
                new SimulatorWebhookSigner(KEY_ID, SECRET), Clock.systemUTC())) {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                assertThrows(IllegalStateException.class, () -> sender.send(claim));
            } finally {
                TransactionSynchronizationManager.clear();
            }
        }
        store.recordTransportFailure(claim, "TEST_CLEANUP");
    }

    private void insertProviderTransaction() {
        UUID transactionId = UUID.randomUUID();
        UUID resultId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO simulator.provider_transactions
                    (transaction_id, provider_client_id, provider_idempotency_key,
                     request_intent_hash, request_content_hash, scenario,
                     result_category, provider_result_id, provider_reference,
                     created_at, updated_at)
                VALUES (?, 'ledgerops-core-test', ?, ?, ?, 'SUCCESS', 'SUCCESS', ?, ?, ?, ?)
                """, transactionId, "payment:" + paymentId, "a".repeat(64), "b".repeat(64),
                resultId, "SIM-" + transactionId, Timestamp.from(now), Timestamp.from(now));
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
