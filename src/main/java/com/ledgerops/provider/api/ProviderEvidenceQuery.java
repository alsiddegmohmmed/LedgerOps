package com.ledgerops.provider.api;

import java.util.Optional;
import java.util.UUID;

public interface ProviderEvidenceQuery {
    Optional<ProviderEvidence> find(UUID tenantId, UUID evidenceId);
}
