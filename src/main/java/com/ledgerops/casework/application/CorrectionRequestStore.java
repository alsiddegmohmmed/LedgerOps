package com.ledgerops.casework.application;

import com.ledgerops.casework.domain.CorrectionRequest;

import java.util.Optional;
import java.util.UUID;

public interface CorrectionRequestStore {

    CorrectionRequest insertIfAbsent(CorrectionRequest candidate);

    Optional<CorrectionRequest> findByTenantAndId(UUID tenantId, UUID correctionId);

    Optional<CorrectionRequest> lockByTenantAndId(UUID tenantId, UUID correctionId);

    Optional<CorrectionRequest> findCompletedForCase(
            UUID tenantId,
            UUID caseId,
            UUID discrepancyId
    );

    void save(CorrectionRequest request);
}
