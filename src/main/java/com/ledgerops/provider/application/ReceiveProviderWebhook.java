package com.ledgerops.provider.application;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
@ConditionalOnProperty(
        name = "ledgerops.provider.webhook.enabled",
        havingValue = "true"
)
public class ReceiveProviderWebhook {
    public static final int MAXIMUM_BODY_BYTES = 256 * 1024;

    private final ProviderWebhookAuthenticator authenticator;
    private final ProviderWebhookPayloadParser parser;
    private final ProviderWebhookStore store;
    private final MeterRegistry meters;

    public ReceiveProviderWebhook(
            ProviderWebhookAuthenticator authenticator,
            ProviderWebhookPayloadParser parser,
            ProviderWebhookStore store,
            MeterRegistry meters
    ) {
        this.authenticator = authenticator;
        this.parser = parser;
        this.store = store;
        this.meters = meters;
    }

    @Transactional
    public ProviderWebhookReceptionResult receive(ProviderWebhookRequest request) {
        byte[] body = request.rawBody() == null ? new byte[0] : request.rawBody();
        String bodyHash = sha256(body);
        if (body.length > MAXIMUM_BODY_BYTES) {
            store.recordPlatformRejection(request, bodyHash, "BODY_TOO_LARGE");
            return result(ProviderWebhookReceptionOutcome.TOO_LARGE);
        }
        ProviderWebhookAuthenticationResult authentication =
                authenticator.authenticate(request);
        if (!authentication.authenticated()) {
            store.recordPlatformRejection(request, bodyHash, authentication.reasonCode());
            return result(ProviderWebhookReceptionOutcome.UNAUTHORIZED);
        }
        ProviderWebhookPayload payload;
        try {
            payload = parser.parse(body, request.eventId());
        } catch (InvalidProviderWebhookPayloadException exception) {
            store.recordInvalidAuthenticated(
                    request, authentication, bodyHash, "INVALID_WEBHOOK_PAYLOAD");
            return result(ProviderWebhookReceptionOutcome.INVALID_PAYLOAD);
        }
        return result(store.receiveAuthenticated(
                request, authentication, payload, bodyHash));
    }

    private ProviderWebhookReceptionResult result(ProviderWebhookReceptionOutcome outcome) {
        meters.counter("ledgerops.provider.webhook.receipt", "outcome",
                outcome.name().toLowerCase(java.util.Locale.ROOT)).increment();
        return new ProviderWebhookReceptionResult(outcome);
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
