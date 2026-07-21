package com.ledgerops.simulator;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

final class SimulatorWebhookSigner {
    private final String keyId;
    private final byte[] secret;

    SimulatorWebhookSigner(String keyId, String secret) {
        if (keyId == null || keyId.isBlank() || secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Webhook signing configuration must not be blank");
        }
        this.keyId = keyId;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    String keyId() {
        return keyId;
    }

    String sign(String timestamp, String eventId, byte[] body) {
        try {
            String bodyHash = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(body));
            String canonical = String.join("\n", "v1", "POST",
                    "/internal/provider/v1/webhooks", keyId, timestamp, eventId, bodyHash);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return "v1=" + Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }
}
