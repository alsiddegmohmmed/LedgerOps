package com.ledgerops.provider.api;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record ProviderScenarioSnapshot(
        UUID profileId,
        long profileVersion,
        ProviderSubmissionOutcome submissionOutcome,
        ProviderWebhookMode webhookMode,
        ProviderSettlementMode settlementMode,
        long delayMillis,
        String fixtureId,
        Map<String, String> parameters,
        Instant pinnedAt
) {

    public ProviderScenarioSnapshot {
        Objects.requireNonNull(profileId, "Scenario profile ID must not be null");
        Objects.requireNonNull(submissionOutcome, "Submission outcome must not be null");
        Objects.requireNonNull(webhookMode, "Webhook mode must not be null");
        Objects.requireNonNull(settlementMode, "Settlement mode must not be null");
        Objects.requireNonNull(parameters, "Scenario parameters must not be null");
        Objects.requireNonNull(pinnedAt, "Scenario pin time must not be null");
        parameters = Map.copyOf(parameters);
    }

    public static ProviderScenarioSnapshot from(ProviderScenarioProfile profile, Instant pinnedAt) {
        return new ProviderScenarioSnapshot(
                profile.profileId(), profile.version(), profile.submissionOutcome(),
                profile.webhookMode(), profile.settlementMode(), profile.delayMillis(),
                profile.fixtureId(), profile.parameters(), pinnedAt);
    }
}
