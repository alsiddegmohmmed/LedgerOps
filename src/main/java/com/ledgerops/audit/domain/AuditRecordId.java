package com.ledgerops.audit.domain;

import java.util.Objects;
import java.util.UUID;

public record AuditRecordId(UUID value) {

    public AuditRecordId {
        Objects.requireNonNull(value, "Audit record ID must not be null");
    }

    public static AuditRecordId newId() {
        return new AuditRecordId(UUID.randomUUID());
    }
}
