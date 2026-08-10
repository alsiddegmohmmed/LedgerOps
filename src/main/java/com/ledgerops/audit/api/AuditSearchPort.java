package com.ledgerops.audit.api;

public interface AuditSearchPort {

    AuditSearchPage findPage(AuditSearchQuery query);
}
