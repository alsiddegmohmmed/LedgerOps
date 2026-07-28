package com.ledgerops.identity.domain;

public record KeycloakIdentity(String issuer, String subject) {

    public KeycloakIdentity {
        issuer = requireValue(issuer, "Issuer");
        subject = requireValue(subject, "Subject");
    }

    private static String requireValue(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidApplicationUserException(field + " must not be blank");
        }
        return value;
    }
}
