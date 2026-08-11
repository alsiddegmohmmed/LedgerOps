package com.ledgerops.reconciliation.api;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

record ReconciliationRunHttpRequest(
        @NotNull UUID batchVersionId,
        @NotNull String rulesVersion,
        @NotNull Instant sourceCutoff,
        @AssertTrue(message = "confirmation must be true") boolean confirmation
) {
}
