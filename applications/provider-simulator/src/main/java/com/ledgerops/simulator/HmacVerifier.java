package com.ledgerops.simulator;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Component
final class HmacVerifier {
    private final String keyId;
    private final byte[] secret;
    private final Clock clock;

    HmacVerifier(
            @Value("${ledgerops.simulator.core-key-id}") String keyId,
            @Value("${ledgerops.simulator.core-secret}") String secret,
            Clock clock
    ) {
        if (keyId.isBlank() || secret.isBlank()) {
            throw new IllegalArgumentException("Simulator HMAC credentials must be configured");
        }
        this.keyId = keyId;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    boolean verify(String method, String path, String suppliedKeyId, String timestamp,
                   String requestId, byte[] body, String signature) {
        try {
            if (!keyId.equals(suppliedKeyId)) return false;
            if (!timestamp.matches("^(0|[1-9][0-9]*)$")) return false;
            UUID parsedRequestId = UUID.fromString(requestId);
            if (!parsedRequestId.toString().equals(requestId)) return false;
            long epoch = Long.parseLong(timestamp);
            if (Math.abs(clock.instant().getEpochSecond() - epoch) > 300) return false;
            String bodyHash = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(body));
            String canonical = String.join("\n", "v1", method.toUpperCase(), path,
                    suppliedKeyId, timestamp, requestId, bodyHash);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            if (signature == null || !signature.matches("^v1=[A-Za-z0-9_-]{43}$")) {
                return false;
            }
            byte[] suppliedMac = Base64.getUrlDecoder().decode(signature.substring(3));
            byte[] expectedMac = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(expectedMac, suppliedMac);
        } catch (Exception exception) {
            return false;
        }
    }
}
