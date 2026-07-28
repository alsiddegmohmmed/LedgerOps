package com.ledgerops.identity.api;

public record AuthenticatedPrincipal(
        String principalType,
        String issuer,
        String subject
) {
}
