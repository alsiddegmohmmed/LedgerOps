package com.ledgerops.audit.domain;

public record AuditTargetType(String value) {

    public AuditTargetType {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Audit target type must not be blank");
        }
    }
}
