package com.ledgerops.reconciliation.api;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

record ReconciliationPostingHttpRequest(
        @NotNull UUID batchFamilyId,
        @AssertTrue(message = "confirmation must be true") boolean confirmation,
        @NotBlank(message = "reason is required")
        @Size(max = 512, message = "reason must be at most 512 characters") String reason
) {
}
