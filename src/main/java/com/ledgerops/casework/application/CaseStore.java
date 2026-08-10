package com.ledgerops.casework.application;

import com.ledgerops.casework.domain.CaseFile;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CaseStore {
    CaseFile insertIfAbsent(CaseFile candidate);
    Optional<CaseFile> findByTenantAndId(UUID tenantId, UUID caseId);
    Optional<CaseFile> lockByTenantAndId(UUID tenantId, UUID caseId);
    List<CaseFile> queue(UUID tenantId);
    void save(CaseFile caseFile);
}
