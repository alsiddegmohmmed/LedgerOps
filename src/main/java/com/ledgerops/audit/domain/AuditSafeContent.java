package com.ledgerops.audit.domain;

import java.util.Locale;

final class AuditSafeContent {

    private static final String[] FORBIDDEN_MARKERS = {
            "password",
            "bearer ",
            "authorization:",
            "access_token",
            "refresh_token",
            "client_secret",
            "private_key",
            "cookie:"
    };

    private AuditSafeContent() {
    }

    static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }

        String normalized = value.toLowerCase(Locale.ROOT);
        for (String marker : FORBIDDEN_MARKERS) {
            if (normalized.contains(marker)) {
                throw new IllegalArgumentException(field + " contains prohibited sensitive content");
            }
        }
        return value;
    }
}
