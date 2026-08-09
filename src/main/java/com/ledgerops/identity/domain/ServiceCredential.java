package com.ledgerops.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Core-owned state for one sandbox service credential.
 *
 * <p>The Keycloak client secret is deliberately absent. It may exist only in
 * the short-lived provisioning response and is never part of this aggregate.</p>
 */
public final class ServiceCredential {
    private static final String CLIENT_ID_PREFIX = "ledgerops-sandbox-credential-";

    private final ServiceCredentialId id;
    private final UUID tenantId;
    private final UUID merchantId;
    private final String label;
    private final String keycloakClientId;
    private final ServiceCredentialStatus status;
    private final ApplicationUserId createdBy;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final ServiceCredentialId replacesCredentialId;
    private final CredentialProvisioningOperationId provisioningOperationId;
    private final CredentialDisclosureStatus disclosureStatus;
    private final Instant disclosureConsumedAt;

    private ServiceCredential(
            ServiceCredentialId id,
            UUID tenantId,
            UUID merchantId,
            String label,
            String keycloakClientId,
            ServiceCredentialStatus status,
            ApplicationUserId createdBy,
            Instant createdAt,
            Instant updatedAt,
            ServiceCredentialId replacesCredentialId,
            CredentialProvisioningOperationId provisioningOperationId,
            CredentialDisclosureStatus disclosureStatus,
            Instant disclosureConsumedAt
    ) {
        this.id = Objects.requireNonNull(id, "Service credential ID must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "Credential Tenant ID must not be null");
        this.merchantId = Objects.requireNonNull(merchantId, "Credential Merchant ID must not be null");
        this.label = requireText(label, "Credential label");
        this.keycloakClientId = requireText(keycloakClientId, "Keycloak client ID");
        if (!keycloakClientId.equals(deterministicClientId(id))) {
            throw new IllegalArgumentException("Keycloak client ID must be deterministic from credential ID");
        }
        this.status = Objects.requireNonNull(status, "Service credential status must not be null");
        this.createdBy = Objects.requireNonNull(createdBy, "Credential creator must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "Credential creation time must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "Credential update time must not be null");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("Credential update time must not precede creation time");
        }
        if (replacesCredentialId != null && replacesCredentialId.equals(id)) {
            throw new IllegalArgumentException("A credential cannot replace itself");
        }
        this.replacesCredentialId = replacesCredentialId;
        this.provisioningOperationId = Objects.requireNonNull(
                provisioningOperationId,
                "Provisioning operation ID must not be null"
        );
        this.disclosureStatus = Objects.requireNonNull(
                disclosureStatus,
                "Credential disclosure status must not be null"
        );
        this.disclosureConsumedAt = disclosureConsumedAt;
        if (disclosureStatus == CredentialDisclosureStatus.PENDING && disclosureConsumedAt != null) {
            throw new IllegalArgumentException("Pending disclosure cannot have a consumed timestamp");
        }
        if (disclosureStatus == CredentialDisclosureStatus.CONSUMED && disclosureConsumedAt == null) {
            throw new IllegalArgumentException("Consumed disclosure requires a consumed timestamp");
        }
        if (status == ServiceCredentialStatus.PROVISIONING && disclosureStatus != CredentialDisclosureStatus.PENDING) {
            throw new IllegalArgumentException("Provisioning credential must have pending disclosure");
        }
    }

    public static ServiceCredential provisioning(
            ServiceCredentialId id,
            UUID tenantId,
            UUID merchantId,
            String label,
            ApplicationUserId createdBy,
            CredentialProvisioningOperationId provisioningOperationId,
            Instant now
    ) {
        return provisioning(
                id,
                tenantId,
                merchantId,
                label,
                createdBy,
                provisioningOperationId,
                null,
                now
        );
    }

    public static ServiceCredential provisioning(
            ServiceCredentialId id,
            UUID tenantId,
            UUID merchantId,
            String label,
            ApplicationUserId createdBy,
            CredentialProvisioningOperationId provisioningOperationId,
            ServiceCredentialId replacesCredentialId,
            Instant now
    ) {
        Objects.requireNonNull(now, "Credential creation time must not be null");
        return new ServiceCredential(
                id,
                tenantId,
                merchantId,
                label,
                deterministicClientId(id),
                ServiceCredentialStatus.PROVISIONING,
                createdBy,
                now,
                now,
                replacesCredentialId,
                provisioningOperationId,
                CredentialDisclosureStatus.PENDING,
                null
        );
    }

    public static ServiceCredential rehydrate(
            ServiceCredentialId id,
            UUID tenantId,
            UUID merchantId,
            String label,
            String keycloakClientId,
            ServiceCredentialStatus status,
            ApplicationUserId createdBy,
            Instant createdAt,
            Instant updatedAt,
            ServiceCredentialId replacesCredentialId,
            CredentialProvisioningOperationId provisioningOperationId,
            CredentialDisclosureStatus disclosureStatus,
            Instant disclosureConsumedAt
    ) {
        return new ServiceCredential(
                id,
                tenantId,
                merchantId,
                label,
                keycloakClientId,
                status,
                createdBy,
                createdAt,
                updatedAt,
                replacesCredentialId,
                provisioningOperationId,
                disclosureStatus,
                disclosureConsumedAt
        );
    }

    public ServiceCredential activate(Instant now) {
        requireCurrent(ServiceCredentialStatus.PROVISIONING, "activate");
        Objects.requireNonNull(now, "Credential activation time must not be null");
        return copy(
                ServiceCredentialStatus.ACTIVE,
                now,
                CredentialDisclosureStatus.CONSUMED,
                now
        );
    }

    public ServiceCredential fail(Instant now) {
        requireCurrent(ServiceCredentialStatus.PROVISIONING, "fail");
        Objects.requireNonNull(now, "Credential failure time must not be null");
        return copy(ServiceCredentialStatus.FAILED, now, disclosureStatus, disclosureConsumedAt);
    }

    public ServiceCredential retryProvisioning(Instant now) {
        requireCurrent(ServiceCredentialStatus.FAILED, "retry provisioning");
        Objects.requireNonNull(now, "Credential retry time must not be null");
        return copy(ServiceCredentialStatus.PROVISIONING, now,
                CredentialDisclosureStatus.PENDING, null);
    }

    public ServiceCredential revoke(Instant now) {
        if (status == ServiceCredentialStatus.REVOKED) {
            throw invalid("revoke");
        }
        Objects.requireNonNull(now, "Credential revocation time must not be null");
        return copy(ServiceCredentialStatus.REVOKED, now, disclosureStatus, disclosureConsumedAt);
    }

    public static String deterministicClientId(ServiceCredentialId id) {
        Objects.requireNonNull(id, "Service credential ID must not be null");
        return CLIENT_ID_PREFIX + id.value().toString().toLowerCase();
    }

    public ServiceCredentialId id() { return id; }
    public UUID tenantId() { return tenantId; }
    public UUID merchantId() { return merchantId; }
    public String label() { return label; }
    public String keycloakClientId() { return keycloakClientId; }
    public ServiceCredentialStatus status() { return status; }
    public ApplicationUserId createdBy() { return createdBy; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public ServiceCredentialId replacesCredentialId() { return replacesCredentialId; }
    public CredentialProvisioningOperationId provisioningOperationId() { return provisioningOperationId; }
    public CredentialDisclosureStatus disclosureStatus() { return disclosureStatus; }
    public Instant disclosureConsumedAt() { return disclosureConsumedAt; }

    private ServiceCredential copy(
            ServiceCredentialStatus nextStatus,
            Instant nextUpdatedAt,
            CredentialDisclosureStatus nextDisclosureStatus,
            Instant nextDisclosureConsumedAt
    ) {
        return new ServiceCredential(
                id,
                tenantId,
                merchantId,
                label,
                keycloakClientId,
                nextStatus,
                createdBy,
                createdAt,
                nextUpdatedAt,
                replacesCredentialId,
                provisioningOperationId,
                nextDisclosureStatus,
                nextDisclosureConsumedAt
        );
    }

    private void requireCurrent(ServiceCredentialStatus expected, String operation) {
        if (status != expected) {
            throw invalid(operation);
        }
    }

    private InvalidServiceCredentialTransitionException invalid(String operation) {
        return new InvalidServiceCredentialTransitionException(
                "Credential " + id.value() + " cannot " + operation + " from " + status
        );
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value.trim();
    }
}
