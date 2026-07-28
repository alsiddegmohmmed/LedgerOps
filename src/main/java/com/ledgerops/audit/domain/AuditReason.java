package com.ledgerops.audit.domain;

public record AuditReason(String value) {

    public AuditReason {
        value = AuditSafeContent.require(value, "Audit reason");
    }
}
