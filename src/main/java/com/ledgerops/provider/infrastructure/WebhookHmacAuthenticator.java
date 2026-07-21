package com.ledgerops.provider.infrastructure;

import com.ledgerops.provider.application.ProviderWebhookAuthenticationResult;
import com.ledgerops.provider.application.ProviderWebhookAuthenticator;
import com.ledgerops.provider.application.ProviderWebhookRequest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.UUID;

final class WebhookHmacAuthenticator implements ProviderWebhookAuthenticator {
    static final String PATH = "/internal/provider/v1/webhooks";
    private static final long TOLERANCE_SECONDS = 300;
    private final String inboundKeyId;
    private final byte[] inboundSecret;
    private final String providerClientId;
    private final Clock clock;

    WebhookHmacAuthenticator(
            String inboundKeyId,
            String inboundSecret,
            String providerClientId,
            Clock clock
    ) {
        if (inboundKeyId == null || inboundKeyId.isBlank()
                || inboundSecret == null || inboundSecret.isBlank()
                || providerClientId == null || providerClientId.isBlank()) {
            throw new IllegalArgumentException("Webhook key configuration must not be blank");
        }
        this.inboundKeyId = inboundKeyId;
        this.inboundSecret = inboundSecret.getBytes(StandardCharsets.UTF_8);
        this.providerClientId = providerClientId;
        this.clock = clock;
    }

    @Override
    public ProviderWebhookAuthenticationResult authenticate(ProviderWebhookRequest request) {
        if (!inboundKeyId.equals(request.keyId())) {
            return ProviderWebhookAuthenticationResult.rejected(
                    "UNKNOWN_OR_WRONG_DIRECTION_KEY");
        }
        long timestamp;
        try {
            if (request.timestamp() == null
                    || !request.timestamp().matches("0|[1-9][0-9]*")) {
                throw new IllegalArgumentException();
            }
            timestamp = Long.parseLong(request.timestamp());
            if (Math.abs(Math.subtractExact(clock.instant().getEpochSecond(), timestamp))
                    > TOLERANCE_SECONDS) {
                return ProviderWebhookAuthenticationResult.rejected("INVALID_TIMESTAMP");
            }
        } catch (RuntimeException exception) {
            return ProviderWebhookAuthenticationResult.rejected("INVALID_TIMESTAMP");
        }
        try {
            UUID parsed = UUID.fromString(request.eventId());
            if (!parsed.toString().equals(request.eventId())) {
                return ProviderWebhookAuthenticationResult.rejected("INVALID_EVENT_ID");
            }
        } catch (RuntimeException exception) {
            return ProviderWebhookAuthenticationResult.rejected("INVALID_EVENT_ID");
        }
        byte[] supplied = decode(request.signature());
        if (supplied == null || !MessageDigest.isEqual(
                supplied, mac(canonical(request)))) {
            return ProviderWebhookAuthenticationResult.rejected("INVALID_SIGNATURE");
        }
        return ProviderWebhookAuthenticationResult.accepted(
                "SIMULATOR", providerClientId);
    }

    private String canonical(ProviderWebhookRequest request) {
        return "v1\nPOST\n" + PATH + "\n" + request.keyId() + "\n"
                + request.timestamp() + "\n" + request.eventId() + "\n"
                + ProviderHmacSigner.hash(request.rawBody());
    }

    private byte[] mac(String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(inboundSecret, "HmacSHA256"));
            return mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    private byte[] decode(String signature) {
        if (signature == null || !signature.startsWith("v1=")
                || signature.length() <= 3 || signature.substring(3).contains("=")) {
            return null;
        }
        try {
            return Base64.getUrlDecoder().decode(signature.substring(3));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    String signForTest(String timestamp, String eventId, byte[] body) {
        ProviderWebhookRequest request = new ProviderWebhookRequest(
                body, inboundKeyId, timestamp, eventId, null, UUID.randomUUID(), clock.instant());
        return "v1=" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac(canonical(request)));
    }
}
