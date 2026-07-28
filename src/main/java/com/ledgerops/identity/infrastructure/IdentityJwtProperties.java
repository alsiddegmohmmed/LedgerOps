package com.ledgerops.identity.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ledgerops.identity.jwt")
record IdentityJwtProperties(String issuer, String audience, String jwkSetUri) {

    boolean isConfigured() {
        return hasText(issuer) && hasText(audience) && hasText(jwkSetUri);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
