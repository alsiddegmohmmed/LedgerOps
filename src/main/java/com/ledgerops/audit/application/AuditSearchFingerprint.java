package com.ledgerops.audit.application;

import com.ledgerops.audit.api.AuditSearchQuery;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

final class AuditSearchFingerprint {

    private AuditSearchFingerprint() {
    }

    static String of(AuditSearchQuery query) {
        String canonical = String.join("|",
                query.tenantId().toString(),
                optional(query.actorIssuer()),
                optional(query.actorSubject()),
                optional(query.action()),
                optional(query.entity()),
                optional(query.entityId()),
                optional(query.fromInclusive()),
                optional(query.toExclusive()),
                optional(query.result()),
                optional(query.correlationId()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static String optional(Object value) {
        return Objects.toString(value, "-");
    }
}
