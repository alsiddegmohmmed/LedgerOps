package com.ledgerops.simulator;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HmacFixtureCompatibilityTests {
    @Test
    void simulatorVerifiesTheSameCanonicalFixtureProducedByCore() throws Exception {
        var fixture = JsonMapper.builder().build().readTree(Path.of(
                "packages/provider-contracts/v1/fixtures/hmac-core-to-simulator.json").toFile());
        long timestamp = Long.parseLong(fixture.required("timestamp").asString());
        HmacVerifier verifier = new HmacVerifier(
                fixture.required("keyId").asString(), fixture.required("testSecret").asString(),
                Clock.fixed(Instant.ofEpochSecond(timestamp), ZoneOffset.UTC));

        assertTrue(verifier.verify(fixture.required("method").asString(),
                fixture.required("rawPath").asString(), fixture.required("keyId").asString(),
                fixture.required("timestamp").asString(), fixture.required("requestId").asString(),
                fixture.required("rawBody").asString().getBytes(),
                fixture.required("signature").asString()));
        assertFalse(verifier.verify(fixture.required("method").asString(),
                fixture.required("rawPath").asString(), "wrong-direction-key",
                fixture.required("timestamp").asString(), fixture.required("requestId").asString(),
                fixture.required("rawBody").asString().getBytes(),
                fixture.required("signature").asString()));
        assertFalse(verifier.verify(fixture.required("method").asString(),
                fixture.required("rawPath").asString(), fixture.required("keyId").asString(),
                "0" + fixture.required("timestamp").asString(),
                fixture.required("requestId").asString(),
                fixture.required("rawBody").asString().getBytes(),
                fixture.required("signature").asString()));
        assertFalse(verifier.verify(fixture.required("method").asString(),
                fixture.required("rawPath").asString(), fixture.required("keyId").asString(),
                fixture.required("timestamp").asString(),
                fixture.required("requestId").asString().toUpperCase(java.util.Locale.ROOT),
                fixture.required("rawBody").asString().getBytes(),
                fixture.required("signature").asString()));
        assertFalse(verifier.verify(fixture.required("method").asString(),
                fixture.required("rawPath").asString(), fixture.required("keyId").asString(),
                fixture.required("timestamp").asString(), fixture.required("requestId").asString(),
                fixture.required("rawBody").asString().getBytes(), "v1=%%%"));
    }
}
