package com.ledgerops.risk.api;

import com.ledgerops.risk.domain.RiskProfile;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RiskConfigurationStore {

    Optional<RiskProfile> findActiveProfile(UUID tenantId);

    List<RiskProfile> findProfileHistory(UUID tenantId);

    RiskProfile appendActiveProfile(RiskProfile profile, Long expectedActiveVersion);
}
