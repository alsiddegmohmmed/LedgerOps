package com.ledgerops.casework.api;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface CaseworkPort {
    CaseSnapshot createIfAbsent(CaseCreationRequest request);
    Optional<CaseSnapshot> findByTenantAndId(UUID tenantId, UUID caseId);
    List<CaseSnapshot> queue(UUID tenantId);
    List<CaseSnapshot> queue(UUID tenantId, Set<UUID> merchantIds);
    CaseSnapshot assign(CaseAssignmentRequest request);
    CaseSnapshot addNote(CaseNoteRequest request);
    CaseSnapshot transition(CaseTransitionRequest request);
    CaseSnapshot resolve(CaseResolutionRequest request);
    CaseSnapshot close(CaseCloseRequest request);
}
