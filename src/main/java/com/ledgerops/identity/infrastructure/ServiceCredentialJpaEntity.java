package com.ledgerops.identity.infrastructure;

import com.ledgerops.identity.domain.ApplicationUserId;
import com.ledgerops.identity.domain.CredentialDisclosureStatus;
import com.ledgerops.identity.domain.CredentialProvisioningOperationId;
import com.ledgerops.identity.domain.ServiceCredential;
import com.ledgerops.identity.domain.ServiceCredentialId;
import com.ledgerops.identity.domain.ServiceCredentialStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
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

    @Column(nullable = false, length = 255)
    private String label;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "replaces_credential_id")
    private UUID replacesCredentialId;

    @Column(name = "provisioning_operation_id", nullable = false)
    private UUID provisioningOperationId;

    @Column(name = "disclosure_status", nullable = false, length = 16)
    private String disclosureStatus;

    @Column(name = "disclosure_consumed_at")
    private Instant disclosureConsumedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ServiceCredentialJpaEntity() {
    }

    ServiceCredentialJpaEntity(ServiceCredential credential) {
        this.id = credential.id().value();
        this.applicationUserId = credential.createdBy().value();
        this.clientId = credential.keycloakClientId();
        this.tenantId = credential.tenantId();
        this.merchantId = credential.merchantId();
        this.label = credential.label();
        this.status = credential.status().name();
        this.replacesCredentialId = credential.replacesCredentialId() == null
                ? null : credential.replacesCredentialId().value();
        this.provisioningOperationId = credential.provisioningOperationId().value();
        this.disclosureStatus = credential.disclosureStatus().name();
        this.disclosureConsumedAt = credential.disclosureConsumedAt();
        this.createdAt = credential.createdAt();
        this.updatedAt = credential.updatedAt();
    }

    void updateFrom(ServiceCredential credential) {
        if (!id.equals(credential.id().value())
                || !applicationUserId.equals(credential.createdBy().value())
                || !tenantId.equals(credential.tenantId())
                || !merchantId.equals(credential.merchantId())
                || !clientId.equals(credential.keycloakClientId())
                || !createdAt.equals(credential.createdAt())) {
            throw new IllegalArgumentException("Service credential identity is immutable");
        }
        label = credential.label();
        status = credential.status().name();
        replacesCredentialId = credential.replacesCredentialId() == null
                ? null : credential.replacesCredentialId().value();
        provisioningOperationId = credential.provisioningOperationId().value();
        disclosureStatus = credential.disclosureStatus().name();
        disclosureConsumedAt = credential.disclosureConsumedAt();
        updatedAt = credential.updatedAt();
    }

    ServiceCredential toDomain() {
        return ServiceCredential.rehydrate(
                ServiceCredentialId.from(id),
                tenantId,
                merchantId,
                label,
                clientId,
                ServiceCredentialStatus.valueOf(status),
                new ApplicationUserId(applicationUserId),
                createdAt,
                updatedAt,
                replacesCredentialId == null ? null : ServiceCredentialId.from(replacesCredentialId),
                CredentialProvisioningOperationId.from(provisioningOperationId),
                CredentialDisclosureStatus.valueOf(disclosureStatus),
                disclosureConsumedAt
        );
    }

    UUID id() { return id; }
    UUID applicationUserId() { return applicationUserId; }
    String clientId() { return clientId; }
    String label() { return label; }
    UUID tenantId() { return tenantId; }
    UUID merchantId() { return merchantId; }
    String status() { return status; }
    UUID replacesCredentialId() { return replacesCredentialId; }
    UUID provisioningOperationId() { return provisioningOperationId; }
    String disclosureStatus() { return disclosureStatus; }
    Instant disclosureConsumedAt() { return disclosureConsumedAt; }
    Instant createdAt() { return createdAt; }
    Instant updatedAt() { return updatedAt; }
}
