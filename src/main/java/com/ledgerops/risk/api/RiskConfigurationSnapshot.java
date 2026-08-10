package com.ledgerops.risk.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record RiskConfigurationSnapshot(
        UUID tenantId,
        UUID profileId,
        long version,
        int reviewThreshold,
        int rejectThreshold,
        boolean active,
        Instant createdAt,
        List<RiskRuleConfiguration> rules
) {

    public RiskConfigurationSnapshot {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(profileId, "Profile ID must not be null");
        Objects.requireNonNull(createdAt, "Creation time must not be null");
        rules = List.copyOf(Objects.requireNonNull(rules, "Risk rules must not be null"));
    }
}
