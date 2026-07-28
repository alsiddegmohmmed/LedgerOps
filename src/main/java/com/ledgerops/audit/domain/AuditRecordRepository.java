package com.ledgerops.audit.domain;

public interface AuditRecordRepository {

    AuditRecord append(AuditRecord record);
}
