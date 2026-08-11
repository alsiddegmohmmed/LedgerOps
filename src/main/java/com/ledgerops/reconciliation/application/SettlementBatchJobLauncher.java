package com.ledgerops.reconciliation.application;

import java.util.UUID;

public interface SettlementBatchJobLauncher {
    void launch(UUID tenantId, UUID batchVersionId);
}
