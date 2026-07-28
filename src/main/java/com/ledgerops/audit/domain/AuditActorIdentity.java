package com.ledgerops.audit.domain;

public record AuditActorIdentity(String issuer, String subject) {

    public AuditActorIdentity {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("Audit actor issuer must not be blank");
        }
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("Audit actor subject must not be blank");
        }
    }
}
