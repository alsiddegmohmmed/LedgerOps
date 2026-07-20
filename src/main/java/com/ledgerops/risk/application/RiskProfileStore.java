package com.ledgerops.risk.application;

import com.ledgerops.risk.domain.RiskProfile;

import java.util.UUID;

public interface RiskProfileStore {

    void insert(RiskProfile profile);

    RiskProfile loadActiveProfile(UUID tenantId);
}
