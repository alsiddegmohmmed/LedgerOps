package com.ledgerops.simulator;

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

    SimulatorWebhookSender(URI coreBaseUrl, SimulatorWebhookSigner signer, Clock clock) {
        this.coreBaseUrl = coreBaseUrl;
        this.signer = signer;
        this.clock = clock;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    }

    int send(SimulatorWebhookClaim claim) throws Exception {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Simulator webhook HTTP calls cannot run inside a database transaction");
        }
        byte[] body = claim.payload().getBytes(StandardCharsets.UTF_8);
        String timestamp = Long.toString(clock.instant().getEpochSecond());
        String eventId = claim.providerEventId().toString();
        HttpRequest request = HttpRequest.newBuilder(coreBaseUrl.resolve(
                        "/internal/provider/v1/webhooks"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("X-LedgerOps-Key-Id", signer.keyId())
                .header("X-LedgerOps-Timestamp", timestamp)
                .header("X-LedgerOps-Event-Id", eventId)
                .header("X-LedgerOps-Signature", "INVALID".equals(claim.signatureMode())
                        ? "v1=invalid"
                        : signer.sign(timestamp, eventId, body))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Override
    public void close() {
        client.close();
    }
}
