package com.ledgerops.identity.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("ledgerops.identity.keycloak.admin")
public record KeycloakAdminProperties(
        boolean enabled,
        String baseUrl,
        String realm,
        String clientId,
        String clientSecret,
        Duration connectTimeout,
        Duration responseTimeout
) {

    public KeycloakAdminProperties {
        baseUrl = normalizeBaseUrl(baseUrl);
        realm = defaultText(realm, "ledgerops");
        clientId = defaultText(clientId, "ledgerops-core-admin");
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        responseTimeout = responseTimeout == null ? Duration.ofSeconds(5) : responseTimeout;
        if (connectTimeout.isNegative() || connectTimeout.isZero()) {
            throw new IllegalArgumentException("Keycloak connect timeout must be positive");
        }
        if (responseTimeout.isNegative() || responseTimeout.isZero()) {
            throw new IllegalArgumentException("Keycloak response timeout must be positive");
        }
        if (enabled && (clientSecret == null || clientSecret.isBlank())) {
            throw new IllegalArgumentException(
                    "Keycloak Admin client secret is required when the adapter is enabled");
        }
    }

    URI baseUri() {
        return URI.create(baseUrl);
    }

    private static String normalizeBaseUrl(String value) {
        String normalized = defaultText(value, "http://localhost:8080");
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Keycloak base URL must not be blank");
        }
        return normalized;
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
