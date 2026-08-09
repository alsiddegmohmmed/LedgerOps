package com.ledgerops.administration.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class CredentialPageCursorCodec {

    private static final String VERSION = "1";
    private static final String EMPTY = "-";
    private static final Set<String> STATUSES = Set.of(
            "PROVISIONING", "ACTIVE", "FAILED", "REVOKED");

    private CredentialPageCursorCodec() {
    }

    static String encode(CredentialPageCursor cursor) {
        Objects.requireNonNull(cursor, "Credential cursor must not be null");
        String payload = String.join("|",
                VERSION,
                cursor.tenantId().toString(),
                optional(cursor.merchantId()),
                optional(cursor.status()),
                cursor.createdAt().toString(),
                cursor.credentialId().toString()
        );
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                payload.getBytes(StandardCharsets.UTF_8));
    }

    static CredentialPageCursor decode(String encoded) {
        if (encoded == null || encoded.isBlank() || encoded.length() > 2048) {
            throw new InvalidCredentialCursorException();
        }
        try {
            String payload = new String(
                    Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = payload.split("\\|", -1);
            if (parts.length != 6 || !VERSION.equals(parts[0])) {
                throw new InvalidCredentialCursorException();
            }
            UUID tenantId = UUID.fromString(parts[1]);
            UUID merchantId = optionalUuid(parts[2]);
            String status = optionalStatus(parts[3]);
            Instant createdAt = Instant.parse(parts[4]);
            UUID credentialId = UUID.fromString(parts[5]);
            return new CredentialPageCursor(
                    Integer.parseInt(VERSION),
                    tenantId,
                    merchantId,
                    status,
                    createdAt,
                    credentialId
            );
        } catch (InvalidCredentialCursorException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new InvalidCredentialCursorException();
        }
    }

    private static String optional(Object value) {
        return value == null ? EMPTY : value.toString();
    }

    private static UUID optionalUuid(String value) {
        return EMPTY.equals(value) ? null : UUID.fromString(value);
    }

    private static String optionalStatus(String value) {
        if (EMPTY.equals(value)) {
            return null;
        }
        String status = value.toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(status)) {
            throw new InvalidCredentialCursorException();
        }
        return status;
    }
}

record CredentialPageCursor(
        int version,
        UUID tenantId,
        UUID merchantId,
        String status,
        Instant createdAt,
        UUID credentialId
) {
    CredentialPageCursor {
        Objects.requireNonNull(tenantId, "Cursor Tenant ID must not be null");
        Objects.requireNonNull(createdAt, "Cursor timestamp must not be null");
        Objects.requireNonNull(credentialId, "Cursor credential ID must not be null");
    }
}
