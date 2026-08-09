package com.ledgerops.tenancy.api;

import com.ledgerops.tenancy.domain.OperationalContact;

import java.time.Instant;
import java.util.UUID;

record OperationalContactResponse(
        UUID tenantId,
        UUID contactId,
        long version,
        String displayName,
        String email,
        String purpose,
        boolean active,
        Instant createdAt
) {

    static OperationalContactResponse from(OperationalContact contact) {
        return new OperationalContactResponse(
                contact.tenantId().value(),
                contact.contactId(),
                contact.version(),
                contact.displayName(),
                contact.email(),
                contact.purpose(),
                contact.active(),
                contact.createdAt()
        );
    }
}
