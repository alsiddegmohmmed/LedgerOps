package com.ledgerops.identity.api;

import java.time.Instant;

public record AuthenticatedPrincipal(
        String principalType,
        String issuer,
        String subject,
        Instant authenticationTime
) {

    public AuthenticatedPrincipal(
            String principalType,
            String issuer,
            String subject
    ) {
        this(principalType, issuer, subject, null);
    }
}
