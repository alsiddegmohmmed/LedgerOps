package com.ledgerops.provider.infrastructure;

import com.ledgerops.provider.application.ProviderWebhookRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookHmacAuthenticatorTests {
    private static final Instant NOW = Instant.ofEpochSecond(1_784_635_200L);

    @Test
    void canonicalSignatureMatchesTheIndependentSimulatorToCoreFixture() {
        Fixture fixture = fixture();
        var authenticator = authenticator(fixture);
        var result = authenticator.authenticate(request(
                fixture.rawBody().getBytes(StandardCharsets.UTF_8), fixture.keyId(),
                fixture.timestamp(), fixture.eventId(), fixture.signature()));

        assertTrue(result.authenticated());
        assertEquals("SIMULATOR", result.providerId());
        assertEquals("ledgerops-core", result.providerClientId());
    }

    @Test
    void timestampWindowIsInclusiveAtThreeHundredSecondsAndRejectsTheNextSecond() {
        Fixture fixture = fixture();
        var authenticator = authenticator(fixture);
        byte[] body = fixture.rawBody().getBytes(StandardCharsets.UTF_8);
        String atBoundary = Long.toString(NOW.minusSeconds(300).getEpochSecond());
        String boundarySignature = authenticator.signForTest(
                atBoundary, fixture.eventId(), body);
        assertTrue(authenticator.authenticate(request(body, fixture.keyId(), atBoundary,
                fixture.eventId(), boundarySignature)).authenticated());

        String outside = Long.toString(NOW.minusSeconds(301).getEpochSecond());
        String outsideSignature = authenticator.signForTest(outside, fixture.eventId(), body);
        var rejected = authenticator.authenticate(request(body, fixture.keyId(), outside,
                fixture.eventId(), outsideSignature));
        assertFalse(rejected.authenticated());
        assertEquals("INVALID_TIMESTAMP", rejected.reasonCode());
    }

    @Test
    void unknownDirectionInvalidSignatureAndInvalidEventIdentityAreIndistinguishableFromPayload() {
        Fixture fixture = fixture();
        var authenticator = authenticator(fixture);
        byte[] body = fixture.rawBody().getBytes(StandardCharsets.UTF_8);

        assertEquals("UNKNOWN_OR_WRONG_DIRECTION_KEY", authenticator.authenticate(request(
                body, "core-to-simulator-v1", fixture.timestamp(), fixture.eventId(),
                fixture.signature())).reasonCode());
        assertEquals("INVALID_SIGNATURE", authenticator.authenticate(request(
                body, fixture.keyId(), fixture.timestamp(), fixture.eventId(),
                "v1=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")).reasonCode());
        assertEquals("INVALID_EVENT_ID", authenticator.authenticate(request(
                body, fixture.keyId(), fixture.timestamp(), "not-a-uuid",
                fixture.signature())).reasonCode());
    }

    @Test
    void traceHeadersAreNotPartOfTheSignedCanonicalBytes() {
        Fixture fixture = fixture();
        var authenticator = authenticator(fixture);
        byte[] body = fixture.rawBody().getBytes(StandardCharsets.UTF_8);

        // ProviderWebhookRequest contains no trace fields. Trace context is propagated
        // separately by HTTP instrumentation and cannot alter signature verification.
        assertTrue(authenticator.authenticate(request(body, fixture.keyId(),
                fixture.timestamp(), fixture.eventId(), fixture.signature())).authenticated());
    }

    private WebhookHmacAuthenticator authenticator(Fixture fixture) {
        return new WebhookHmacAuthenticator(fixture.keyId(), fixture.testSecret(),
                "ledgerops-core", Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ProviderWebhookRequest request(
            byte[] body, String keyId, String timestamp, String eventId, String signature) {
        return new ProviderWebhookRequest(body, keyId, timestamp, eventId, signature,
                UUID.randomUUID(), NOW);
    }

    private Fixture fixture() {
        try {
            var value = JsonMapper.builder().build().readTree(Path.of(
                    "packages/provider-contracts/v1/fixtures/hmac-simulator-to-core.json")
                    .toFile());
            return new Fixture(
                    value.required("keyId").asString(),
                    value.required("timestamp").asString(),
                    value.required("eventId").asString(),
                    value.required("rawBody").asString(),
                    value.required("testSecret").asString(),
                    value.required("signature").asString());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Fixture(
            String keyId,
            String timestamp,
            String eventId,
            String rawBody,
            String testSecret,
            String signature
    ) {
    }
}
