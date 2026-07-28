package com.ledgerops.audit.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface SpringDataAuditRecordRepository
        extends JpaRepository<AuditRecordJpaEntity, UUID> {
}
