package com.ledgerops.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class CredentialProvisioningOperation {
    private final CredentialProvisioningOperationId id;
    private final ServiceCredentialId credentialId;
    private final UUID tenantId;
    private final String keycloakClientId;
    private final CredentialProvisioningOperationStatus status;
    private final int attemptCount;
    private final String failureCode;
    private final String failureDetail;
    private final Instant createdAt;
    private final Instant updatedAt;

    private CredentialProvisioningOperation(
            CredentialProvisioningOperationId id,
            ServiceCredentialId credentialId,
            UUID tenantId,
            String keycloakClientId,
            CredentialProvisioningOperationStatus status,
            int attemptCount,
            String failureCode,
            String failureDetail,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = Objects.requireNonNull(id, "Provisioning operation ID must not be null");
        this.credentialId = Objects.requireNonNull(
                credentialId,
                "Provisioning operation credential ID must not be null"
        );
        this.tenantId = Objects.requireNonNull(tenantId, "Provisioning operation Tenant ID must not be null");
        this.keycloakClientId = requireText(keycloakClientId, "Provisioning operation client ID");
        if (!keycloakClientId.equals(ServiceCredential.deterministicClientId(credentialId))) {
            throw new IllegalArgumentException(
                    "Provisioning operation client ID must be deterministic from credential ID"
            );
        }
        this.status = Objects.requireNonNull(status, "Provisioning operation status must not be null");
        if (attemptCount < 0) {
            throw new IllegalArgumentException("Provisioning attempt count must not be negative");
        }
        this.attemptCount = attemptCount;
        this.failureCode = normalizeNullable(failureCode, "Provisioning failure code");
        this.failureDetail = normalizeNullable(failureDetail, "Provisioning failure detail");
        this.createdAt = Objects.requireNonNull(createdAt, "Provisioning operation creation time must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "Provisioning operation update time must not be null");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("Provisioning operation update time must not precede creation time");
        }
        if (status == CredentialProvisioningOperationStatus.FAILED && failureCode == null) {
            throw new IllegalArgumentException("Failed provisioning operation requires a failure code");
        }
        if (status != CredentialProvisioningOperationStatus.FAILED
                && (failureCode != null || failureDetail != null)) {
            throw new IllegalArgumentException(
                    "Only failed provisioning operations may retain failure evidence"
            );
        }
    }

    public static CredentialProvisioningOperation pending(
            CredentialProvisioningOperationId id,
            ServiceCredentialId credentialId,
            UUID tenantId,
            Instant now
    ) {
        Objects.requireNonNull(now, "Provisioning operation creation time must not be null");
        return new CredentialProvisioningOperation(
                id,
                credentialId,
                tenantId,
                ServiceCredential.deterministicClientId(credentialId),
                CredentialProvisioningOperationStatus.PENDING,
                0,
                null,
                null,
                now,
                now
        );
    }

    public static CredentialProvisioningOperation rehydrate(
            CredentialProvisioningOperationId id,
            ServiceCredentialId credentialId,
            UUID tenantId,
            String keycloakClientId,
            CredentialProvisioningOperationStatus status,
            int attemptCount,
            String failureCode,
            String failureDetail,
            Instant createdAt,
            Instant updatedAt
    ) {
        return new CredentialProvisioningOperation(
                id,
                credentialId,
                tenantId,
                keycloakClientId,
                status,
                attemptCount,
                failureCode,
                failureDetail,
                createdAt,
                updatedAt
        );
    }

    public CredentialProvisioningOperation recordFailure(
            String code,
            String detail,
            Instant now
    ) {
        requireCurrent(CredentialProvisioningOperationStatus.PENDING, "record failure");
        Objects.requireNonNull(now, "Provisioning failure time must not be null");
        return copy(
                CredentialProvisioningOperationStatus.FAILED,
                attemptCount + 1,
                code,
                detail,
                now
        );
    }

    public CredentialProvisioningOperation retry(Instant now) {
        requireCurrent(CredentialProvisioningOperationStatus.FAILED, "retry");
        Objects.requireNonNull(now, "Provisioning retry time must not be null");
        return copy(
                CredentialProvisioningOperationStatus.PENDING,
                attemptCount + 1,
                null,
                null,
                now
        );
    }

    public CredentialProvisioningOperation complete(Instant now) {
        requireCurrent(CredentialProvisioningOperationStatus.PENDING, "complete");
        Objects.requireNonNull(now, "Provisioning completion time must not be null");
        return copy(
                CredentialProvisioningOperationStatus.COMPLETED,
                attemptCount,
                null,
                null,
                now
        );
    }

    public CredentialProvisioningOperation revoke(Instant now) {
        if (status == CredentialProvisioningOperationStatus.REVOKED) {
            throw invalid("revoke");
        }
        Objects.requireNonNull(now, "Provisioning revocation time must not be null");
        return copy(
                CredentialProvisioningOperationStatus.REVOKED,
                attemptCount,
                null,
                null,
                now
        );
    }

    public CredentialProvisioningOperationId id() { return id; }
    public ServiceCredentialId credentialId() { return credentialId; }
    public UUID tenantId() { return tenantId; }
    public String keycloakClientId() { return keycloakClientId; }
    public CredentialProvisioningOperationStatus status() { return status; }
    public int attemptCount() { return attemptCount; }
    public String failureCode() { return failureCode; }
    public String failureDetail() { return failureDetail; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }

    private CredentialProvisioningOperation copy(
            CredentialProvisioningOperationStatus nextStatus,
            int nextAttemptCount,
            String nextFailureCode,
            String nextFailureDetail,
            Instant nextUpdatedAt
    ) {
        return new CredentialProvisioningOperation(
                id,
                credentialId,
                tenantId,
                keycloakClientId,
                nextStatus,
                nextAttemptCount,
                nextFailureCode,
                nextFailureDetail,
                createdAt,
                nextUpdatedAt
        );
    }

    private void requireCurrent(CredentialProvisioningOperationStatus expected, String operation) {
        if (status != expected) {
            throw invalid(operation);
        }
    }

    private InvalidServiceCredentialTransitionException invalid(String operation) {
        return new InvalidServiceCredentialTransitionException(
                "Provisioning operation " + id.value() + " cannot " + operation + " from " + status
        );
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeNullable(String value, String label) {
        if (value == null) {
            return null;
        }
        return requireText(value, label);
    }
}
