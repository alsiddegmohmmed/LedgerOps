package com.ledgerops.audit.application;

import com.ledgerops.audit.api.InvalidAuditCursorException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Slice3AuditContractTests {

    @Test
    void auditCursorRoundTripsItsPositionAndFingerprint() {
        AuditPageCursor cursor = new AuditPageCursor(
                1,
                Instant.parse("2026-08-10T12:00:00Z"),
                UUID.randomUUID(),
                "b".repeat(64));

        AuditPageCursor decoded = AuditPageCursorCodec.decode(AuditPageCursorCodec.encode(cursor));

        assertEquals(cursor, decoded);
    }

    @Test
    void auditCursorRejectsMalformedValues() {
        assertThrows(InvalidAuditCursorException.class, () -> AuditPageCursorCodec.decode("bad"));
        assertThrows(InvalidAuditCursorException.class, () -> AuditPageCursorCodec.decode("a".repeat(2049)));
    }
}
