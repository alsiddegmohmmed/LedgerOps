package com.ledgerops.provider.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProviderScenarioPort {

    ProviderScenarioProfile createProfile(
            ProviderScenarioProfile requested,
            Long expectedPreviousVersion
    );

    ProviderScenarioAssignment assign(
            ProviderScenarioScope scope,
            UUID tenantId,
            UUID paymentId,
            UUID profileId,
            long profileVersion
    );

    Optional<ProviderScenarioProfile> findProfile(UUID profileId, long version);

    List<ProviderScenarioAssignment> assignments();

    ProviderScenarioSnapshot resolveAndPin(
            UUID tenantId,
            UUID paymentId,
            String operationType,
            Instant now
    );

    Optional<ProviderScenarioSnapshot> findPin(
            UUID tenantId,
            UUID paymentId,
            String operationType
    );
}
