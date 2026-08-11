package com.ledgerops.provider.api;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

public interface ProviderEvidenceBatchQuery {

    Map<UUID, ProviderEvidence> findByTenantAndEvidenceIds(
            UUID tenantId,
            Collection<UUID> evidenceIds
    );
}
