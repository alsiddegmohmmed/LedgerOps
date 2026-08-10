package com.ledgerops.provider.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProviderScenarioProfileTests {

    @Test
    void rejectsDelayedWebhookWithoutAConfiguredDelay() {
        assertThrows(IllegalArgumentException.class, () -> new ProviderScenarioProfile(
                UUID.randomUUID(), 1, ProviderSubmissionOutcome.SUCCESS,
                ProviderWebhookMode.DELAYED, ProviderSettlementMode.EXACT,
                0, null, Map.of(), Instant.parse("2026-08-10T00:00:00Z")));
    }

    @Test
    void createsAnImmutableSnapshotThatRetainsTheSelectedVersionAndParameters() {
        UUID profileId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-08-10T00:00:00Z");
        ProviderScenarioProfile profile = new ProviderScenarioProfile(
                profileId, 3, ProviderSubmissionOutcome.TEMPORARY_FAILURE_STATUS_RECOVERY,
                ProviderWebhookMode.OUT_OF_ORDER, ProviderSettlementMode.DATE_MISMATCH,
                250, "fixture-3", Map.of("seed", "17"), createdAt);

        ProviderScenarioSnapshot snapshot = ProviderScenarioSnapshot.from(profile, createdAt.plusSeconds(1));

        assertEquals(profileId, snapshot.profileId());
        assertEquals(3, snapshot.profileVersion());
        assertEquals("17", snapshot.parameters().get("seed"));
        assertEquals(createdAt.plusSeconds(1), snapshot.pinnedAt());
    }
}
