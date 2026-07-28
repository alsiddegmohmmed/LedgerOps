package com.ledgerops.identity.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "service_credentials", schema = "identity")
class ServiceCredentialJpaEntity {

    @Id
    private UUID id;

    @Column(name = "application_user_id", nullable = false)
    private UUID applicationUserId;

    @Column(name = "client_id", nullable = false, length = 255)
    private String clientId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(nullable = false, length = 16)
    private String status;

    protected ServiceCredentialJpaEntity() {
    }

    UUID id() { return id; }
    UUID applicationUserId() { return applicationUserId; }
    String clientId() { return clientId; }
    UUID tenantId() { return tenantId; }
    UUID merchantId() { return merchantId; }
    String status() { return status; }
}
