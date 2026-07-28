package com.ledgerops.audit.domain;

import java.util.Objects;

public record AuditActionType(String value, boolean tenantOwned) {

    public AuditActionType {
        value = requireValue(value, "Audit action type");
    }

    private static String requireValue(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
