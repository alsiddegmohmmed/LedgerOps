package com.ledgerops.identity.application;

import java.util.Objects;

/**
 * Safe, typed failure from the external Keycloak administration boundary.
 * The detail must not contain a client secret or access token.
 */
public final class KeycloakCredentialProvisioningException extends RuntimeException {
    private final String code;

    public KeycloakCredentialProvisioningException(String code, String safeDetail) {
        super(requireText(safeDetail, "Keycloak provisioning failure detail"));
        this.code = requireText(code, "Keycloak provisioning failure code");
    }

    public String code() {
        return code;
    }

    private static String requireText(String value, String label) {
        Objects.requireNonNull(value, label + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value.trim();
    }
}
