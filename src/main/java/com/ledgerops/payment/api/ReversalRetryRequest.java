package com.ledgerops.payment.api;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

record ReversalRetryRequest(
        @NotNull UUID paymentId,
        @NotNull UUID previousAttemptId,
        @NotNull UUID providerEvidenceId,
        @AssertTrue(message = "confirmation must be true") boolean confirmation,
        @NotBlank @Size(max = 512) String reason
) {
}
