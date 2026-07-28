package com.ledgerops.audit.domain;

public record AuditDetails(String value) {

    public AuditDetails {
        value = AuditSafeContent.require(value, "Audit details");
    }

    public static AuditDetails empty() {
        return new AuditDetails("{}");
    }
}
