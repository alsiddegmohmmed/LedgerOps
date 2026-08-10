package com.ledgerops.risk.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

record RiskConfigurationHttpResponse(
        UUID tenantId,
        UUID profileId,
        long version,
        int reviewThreshold,
        int rejectThreshold,
        boolean active,
        Instant createdAt,
        List<RiskRuleConfiguration> rules
) {
    static RiskConfigurationHttpResponse from(RiskConfigurationSnapshot snapshot) {
        return new RiskConfigurationHttpResponse(
                snapshot.tenantId(), snapshot.profileId(), snapshot.version(),
                snapshot.reviewThreshold(), snapshot.rejectThreshold(), snapshot.active(),
                snapshot.createdAt(), snapshot.rules());
    }
}
