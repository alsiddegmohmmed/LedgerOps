package com.ledgerops.identity.infrastructure;

import com.ledgerops.identity.domain.CredentialProvisioningOperation;
import com.ledgerops.identity.domain.CredentialProvisioningOperationId;
import com.ledgerops.identity.domain.CredentialProvisioningOperationStatus;
import com.ledgerops.identity.domain.ServiceCredentialId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "service_credential_provisioning_operations", schema = "identity")
class CredentialProvisioningOperationJpaEntity {

    @Id
    private UUID id;

    @Column(name = "credential_id", nullable = false)
    private UUID credentialId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "keycloak_client_id", nullable = false, length = 255)
    private String keycloakClientId;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "failure_detail", length = 1024)
    private String failureDetail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CredentialProvisioningOperationJpaEntity() {
    }

    CredentialProvisioningOperationJpaEntity(CredentialProvisioningOperation operation) {
        updateFrom(operation);
    }

    void updateFrom(CredentialProvisioningOperation operation) {
        if (id != null && (!id.equals(operation.id().value())
                || !credentialId.equals(operation.credentialId().value())
                || !tenantId.equals(operation.tenantId())
                || !createdAt.equals(operation.createdAt()))) {
            throw new IllegalArgumentException("Provisioning operation identity is immutable");
        }
        id = operation.id().value();
        credentialId = operation.credentialId().value();
        tenantId = operation.tenantId();
        keycloakClientId = operation.keycloakClientId();
        status = operation.status().name();
        attemptCount = operation.attemptCount();
        failureCode = operation.failureCode();
        failureDetail = operation.failureDetail();
        createdAt = operation.createdAt();
        updatedAt = operation.updatedAt();
    }

    CredentialProvisioningOperation toDomain() {
        return CredentialProvisioningOperation.rehydrate(
                CredentialProvisioningOperationId.from(id),
                ServiceCredentialId.from(credentialId),
                tenantId,
                keycloakClientId,
                CredentialProvisioningOperationStatus.valueOf(status),
                attemptCount,
                failureCode,
                failureDetail,
                createdAt,
                updatedAt
        );
    }
}
