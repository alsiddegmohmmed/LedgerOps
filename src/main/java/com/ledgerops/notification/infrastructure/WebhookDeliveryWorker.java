package com.ledgerops.notification.infrastructure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Component
@ConditionalOnProperty(name = "ledgerops.notification.worker.enabled", havingValue = "true")
class WebhookDeliveryWorker {

    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private final JdbcWebhookEndpointStore store;
    private final WebhookSecretCipher cipher;
    private final WebhookUrlPolicy urlPolicy;
    private final Clock clock;
    private final HttpClient http;
    private final String owner;

    WebhookDeliveryWorker(
            JdbcWebhookEndpointStore store,
            WebhookSecretCipher cipher,
            WebhookUrlPolicy urlPolicy,
            Clock clock,
            @org.springframework.beans.factory.annotation.Value(
                    "${ledgerops.notification.worker.owner:${HOSTNAME:local}}") String owner
    ) {
        this.store = store;
        this.cipher = cipher;
        this.urlPolicy = urlPolicy;
        this.clock = clock;
        this.owner = owner;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Scheduled(fixedDelayString = "${ledgerops.notification.worker.delay-ms:250}")
    void processOne() {
        UUID leaseToken = UUID.randomUUID();
        JdbcWebhookEndpointStore.StoredEndpoint claim = store.claim(
                UUID.randomUUID(), owner, leaseToken, clock.instant());
        if (claim == null) return;
        Instant now = clock.instant();
        if (!store.endpointIsActive(claim.endpointId())) {
            store.cancel(claim, now, "ENDPOINT_REVOKED_BEFORE_CALL");
            return;
        }
        DeliveryResult result;
        try {
            result = deliver(claim);
        } catch (WebhookUrlPolicyException exception) {
            result = new DeliveryResult(0, "URL_POLICY_REJECTED", exception.getMessage(), null, false);
        } catch (java.net.http.HttpTimeoutException exception) {
            result = new DeliveryResult(0, "TIMEOUT", "Webhook request timed out", null, true);
        } catch (IOException exception) {
            result = new DeliveryResult(0, "NETWORK_ERROR", "Webhook network call failed", null, true);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            result = new DeliveryResult(0, "INTERRUPTED", "Webhook worker was interrupted", null, true);
        } catch (RuntimeException exception) {
            result = new DeliveryResult(0, "DELIVERY_ERROR", "Webhook delivery failed", null, true);
        }
        store.complete(claim, clock.instant(), result.httpStatus(), result.outcome(),
                bounded(result.summary()), result.responseBodyHash(), result.retryable());
    }

    private DeliveryResult deliver(JdbcWebhookEndpointStore.StoredEndpoint claim)
            throws IOException, InterruptedException {
        URI uri;
        try {
            uri = urlPolicy.validate(claim.endpointUrl());
        } catch (IllegalArgumentException exception) {
            throw new WebhookUrlPolicyException(exception.getMessage());
        }
        String secret = cipher.decrypt(claim.encryptedSecret(), claim.secretNonce(), claim.keyVersion());
        byte[] body = claim.payload().getBytes(StandardCharsets.UTF_8);
        String timestamp = Long.toString(clock.instant().getEpochSecond());
        String signature = signature(
                secret, uri.getRawPath() == null ? "/" : uri.getRawPath(), claim.endpointId(),
                claim.keyVersion(), timestamp, claim.eventId(), body);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("X-LedgerOps-Webhook-Id", claim.deliveryId().toString())
                .header("X-LedgerOps-Webhook-Key-Version", claim.keyVersion())
                .header("X-LedgerOps-Webhook-Timestamp", timestamp)
                .header("X-LedgerOps-Webhook-Event-Id", claim.eventId().toString())
                .header("X-LedgerOps-Webhook-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        byte[] responseBody;
        try (InputStream responseStream = response.body()) {
            responseBody = readBounded(responseStream);
        }
        String responseHash = sha256(responseBody);
        String summary = new String(
                java.util.Arrays.copyOf(responseBody, Math.min(responseBody.length, MAX_RESPONSE_BYTES)),
                StandardCharsets.UTF_8);
        int status = response.statusCode();
        boolean retryable = status >= 500 || status == 408 || status == 429;
        return new DeliveryResult(status, status >= 200 && status < 300 ? "DELIVERED" :
                retryable ? "RETRYABLE_HTTP" : "TERMINAL_HTTP", summary, responseHash, retryable);
    }

    static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(MAX_RESPONSE_BYTES);
        byte[] buffer = new byte[8192];
        int remaining = MAX_RESPONSE_BYTES;
        while (remaining > 0) {
            int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read < 0) break;
            if (read == 0) continue;
            output.write(buffer, 0, read);
            remaining -= read;
        }
        return output.toByteArray();
    }

    private String signature(
            String secret,
            String rawPath,
            UUID endpointId,
            String keyVersion,
            String timestamp,
            UUID eventId,
            byte[] body
    ) {
        String canonical = "v1\nPOST\n" + rawPath + "\n" + endpointId + "\n" + keyVersion
                + "\n" + timestamp + "\n" + eventId + "\n" + sha256(body);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "v1=" + Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("Webhook signature could not be created", exception);
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String bounded(String value) {
        if (value == null) return null;
        return value.length() <= 512 ? value : value.substring(0, 512);
    }

    private record DeliveryResult(
            int httpStatus,
            String outcome,
            String summary,
            String responseBodyHash,
            boolean retryable
    ) {
    }

    private static final class WebhookUrlPolicyException extends RuntimeException {
        private WebhookUrlPolicyException(String message) {
            super(message);
        }
    }
}
