package com.ledgerops.administration.merchant.api;

import com.ledgerops.administration.api.MerchantLifecycleCommand;
import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

record MerchantLifecycleHttpRequest(
        @AssertTrue(message = "confirmation must be true")
        boolean confirmation,

        @NotBlank(message = "reason is required")
        @Size(max = 512, message = "reason must be at most 512 characters")
        String reason
) {

    MerchantLifecycleCommand toCommand(
            UUID tenantId,
            UUID merchantId,
            AuthorizedRequestContext authorization,
            AuthenticatedPrincipal actor,
            UUID correlationId
    ) {
        return new MerchantLifecycleCommand(
                tenantId,
                merchantId,
                confirmation,
                reason,
                authorization,
                actor,
                correlationId,
                UUID.randomUUID()
        );
    }
}
