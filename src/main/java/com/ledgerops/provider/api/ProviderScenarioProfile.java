package com.ledgerops.provider.api;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record ProviderScenarioProfile(
        UUID profileId,
        long version,
        ProviderSubmissionOutcome submissionOutcome,
        ProviderWebhookMode webhookMode,
        ProviderSettlementMode settlementMode,
        long delayMillis,
        String fixtureId,
        Map<String, String> parameters,
        Instant createdAt
) {

    public ProviderScenarioProfile {
        Objects.requireNonNull(profileId, "Scenario profile ID must not be null");
        Objects.requireNonNull(submissionOutcome, "Submission outcome must not be null");
        Objects.requireNonNull(webhookMode, "Webhook mode must not be null");
        Objects.requireNonNull(settlementMode, "Settlement mode must not be null");
        Objects.requireNonNull(createdAt, "Scenario profile creation time must not be null");
        parameters = Map.copyOf(Objects.requireNonNull(parameters, "Scenario parameters must not be null"));
        if (version < 1) throw new IllegalArgumentException("Scenario profile version must be positive");
        if (delayMillis < 0 || delayMillis > 300_000) {
            throw new IllegalArgumentException("Scenario delay must be between 0 and 300000 milliseconds");
        }
        if (webhookMode == ProviderWebhookMode.DELAYED && delayMillis == 0) {
            throw new IllegalArgumentException("Delayed webhook scenarios require a positive delay");
        }
        if (fixtureId != null && fixtureId.isBlank()) {
            throw new IllegalArgumentException("Scenario fixture ID must not be blank");
        }
    }

    public String canonicalKey() {
        return submissionOutcome + "|" + webhookMode + "|" + settlementMode
                + "|" + delayMillis + "|" + (fixtureId == null ? "" : fixtureId)
                + "|" + parameters.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + ";" + right).orElse("");
    }
}
