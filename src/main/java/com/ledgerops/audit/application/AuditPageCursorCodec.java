package com.ledgerops.audit.application;

import com.ledgerops.audit.api.InvalidAuditCursorException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

final class AuditPageCursorCodec {

    private AuditPageCursorCodec() {
    }

    static String encode(AuditPageCursor cursor) {
        String payload = String.join("|", "1", cursor.occurredAt().toString(),
                cursor.auditId().toString(), cursor.queryFingerprint());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                payload.getBytes(StandardCharsets.UTF_8));
    }

    static AuditPageCursor decode(String encoded) {
        if (encoded == null || encoded.isBlank() || encoded.length() > 2048) {
            throw new InvalidAuditCursorException();
        }
        try {
            String payload = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = payload.split("\\|", -1);
            if (parts.length != 4 || !"1".equals(parts[0]) || parts[3].length() != 64) {
                throw new InvalidAuditCursorException();
            }
            return new AuditPageCursor(
                    1, Instant.parse(parts[1]), UUID.fromString(parts[2]), parts[3]);
        } catch (InvalidAuditCursorException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new InvalidAuditCursorException();
        }
    }
}

record AuditPageCursor(int version, Instant occurredAt, UUID auditId, String queryFingerprint) {
}
