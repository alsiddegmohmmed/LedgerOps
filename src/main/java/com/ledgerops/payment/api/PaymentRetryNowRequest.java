package com.ledgerops.payment.api;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

record PaymentRetryNowRequest(
        @AssertTrue(message = "confirmation must be true") boolean confirmation,
        @NotBlank @Size(max = 512) String reason
) {
}
