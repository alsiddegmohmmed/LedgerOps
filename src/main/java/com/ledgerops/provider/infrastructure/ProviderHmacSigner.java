package com.ledgerops.provider.infrastructure;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

final class ProviderHmacSigner {
    private final String keyId;
    private final byte[] secret;

    ProviderHmacSigner(String keyId, String secret) {
        if (keyId == null || keyId.isBlank() || secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Core-to-Provider HMAC credentials are required");
        }
        this.keyId = keyId;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    String keyId() {
        return keyId;
    }

    String sign(String method, String rawPath, String timestamp,
            String requestId, byte[] rawBody) {
        try {
            String bodyHash = hash(rawBody);
            String canonical = String.join("\n", "v1", method.toUpperCase(), rawPath,
                    keyId, timestamp, requestId, bodyHash);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return "v1=" + Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not sign Provider request", exception);
        }
    }

    static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
