package com.ledgerops.identity.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServiceCredentialTests {
    private static final Instant CREATED = Instant.parse("2026-02-01T10:00:00Z");

    @Test
    void startsProvisioningWithDeterministicClientAndPendingDisclosure() {
        ServiceCredentialId credentialId = ServiceCredentialId.newId();
        ServiceCredential credential = provisioning(credentialId);

        assertThat(credential.status()).isEqualTo(ServiceCredentialStatus.PROVISIONING);
        assertThat(credential.keycloakClientId())
                .isEqualTo(ServiceCredential.deterministicClientId(credentialId));
        assertThat(credential.disclosureStatus()).isEqualTo(CredentialDisclosureStatus.PENDING);
        assertThat(credential.disclosureConsumedAt()).isNull();
        assertThat(credential.replacesCredentialId()).isNull();
    }

    @Test
    void activationConsumesDisclosureBeforeTheSecretCanBeReturned() {
        ServiceCredential credential = provisioning(ServiceCredentialId.newId());
        Instant activatedAt = CREATED.plusSeconds(30);

        ServiceCredential active = credential.activate(activatedAt);

        assertThat(credential.status()).isEqualTo(ServiceCredentialStatus.PROVISIONING);
        assertThat(active.status()).isEqualTo(ServiceCredentialStatus.ACTIVE);
        assertThat(active.disclosureStatus()).isEqualTo(CredentialDisclosureStatus.CONSUMED);
        assertThat(active.disclosureConsumedAt()).isEqualTo(activatedAt);
        assertThat(active.updatedAt()).isEqualTo(activatedAt);
    }

    @Test
    void failedProvisioningCanRetryWithTheSameOperationAndClientIdentity() {
        ServiceCredential credential = provisioning(ServiceCredentialId.newId());
        ServiceCredential failed = credential.fail(CREATED.plusSeconds(10));
        ServiceCredential retried = failed.retryProvisioning(CREATED.plusSeconds(20));

        assertThat(failed.status()).isEqualTo(ServiceCredentialStatus.FAILED);
        assertThat(retried.status()).isEqualTo(ServiceCredentialStatus.PROVISIONING);
        assertThat(retried.provisioningOperationId()).isEqualTo(credential.provisioningOperationId());
        assertThat(retried.keycloakClientId()).isEqualTo(credential.keycloakClientId());
        assertThat(retried.disclosureStatus()).isEqualTo(CredentialDisclosureStatus.PENDING);
    }

    @Test
    void revocationIsLocalAndTerminalFromEveryNonTerminalState() {
        ServiceCredentialId id = ServiceCredentialId.newId();
        ServiceCredential provisioning = provisioning(id);
        ServiceCredential failed = provisioning.fail(CREATED.plusSeconds(1));
        ServiceCredential active = provisioning(id).activate(CREATED.plusSeconds(2));

        assertThat(provisioning.revoke(CREATED.plusSeconds(3)).status())
                .isEqualTo(ServiceCredentialStatus.REVOKED);
        assertThat(failed.revoke(CREATED.plusSeconds(4)).status())
                .isEqualTo(ServiceCredentialStatus.REVOKED);
        ServiceCredential revoked = active.revoke(CREATED.plusSeconds(5));
        assertThat(revoked.status()).isEqualTo(ServiceCredentialStatus.REVOKED);

        assertThatThrownBy(() -> revoked.revoke(CREATED.plusSeconds(6)))
                .isExactlyInstanceOf(InvalidServiceCredentialTransitionException.class);
        assertThatThrownBy(() -> revoked.activate(CREATED.plusSeconds(6)))
                .isExactlyInstanceOf(InvalidServiceCredentialTransitionException.class);
    }

    @Test
    void replacementKeepsTheRelationshipWithoutSharingCredentialIdentity() {
        ServiceCredentialId originalId = ServiceCredentialId.newId();
        ServiceCredentialId replacementId = ServiceCredentialId.newId();
        ServiceCredential replacement = ServiceCredential.provisioning(
                replacementId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "replacement",
                ApplicationUserId.newId(),
                CredentialProvisioningOperationId.newId(),
                originalId,
                CREATED
        );

        assertThat(replacement.id()).isNotEqualTo(originalId);
        assertThat(replacement.replacesCredentialId()).isEqualTo(originalId);
    }

    @Test
    void rejectsNonDeterministicClientAndInvalidDisclosureState() {
        ServiceCredentialId id = ServiceCredentialId.newId();

        assertThatThrownBy(() -> ServiceCredential.rehydrate(
                id, UUID.randomUUID(), UUID.randomUUID(), "label", "arbitrary-client",
                ServiceCredentialStatus.ACTIVE, ApplicationUserId.newId(), CREATED, CREATED,
                null, CredentialProvisioningOperationId.newId(),
                CredentialDisclosureStatus.CONSUMED, CREATED
        )).isExactlyInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> ServiceCredential.rehydrate(
                id, UUID.randomUUID(), UUID.randomUUID(), "label",
                ServiceCredential.deterministicClientId(id), ServiceCredentialStatus.PROVISIONING,
                ApplicationUserId.newId(), CREATED, CREATED, null,
                CredentialProvisioningOperationId.newId(), CredentialDisclosureStatus.CONSUMED, CREATED
        )).isExactlyInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void provisioningOperationUsesOneStableIdentityAcrossFailureRetryAndCompletion() {
        ServiceCredentialId credentialId = ServiceCredentialId.newId();
        CredentialProvisioningOperationId operationId = CredentialProvisioningOperationId.newId();
        CredentialProvisioningOperation operation = CredentialProvisioningOperation.pending(
                operationId, credentialId, UUID.randomUUID(), CREATED);

        CredentialProvisioningOperation failed = operation.recordFailure(
                "KEYCLOAK_UNAVAILABLE", "admin endpoint unavailable", CREATED.plusSeconds(1));
        CredentialProvisioningOperation retried = failed.retry(CREATED.plusSeconds(2));
        CredentialProvisioningOperation completed = retried.complete(CREATED.plusSeconds(3));

        assertThat(failed.id()).isEqualTo(operationId);
        assertThat(failed.attemptCount()).isEqualTo(1);
        assertThat(retried.status()).isEqualTo(CredentialProvisioningOperationStatus.PENDING);
        assertThat(retried.attemptCount()).isEqualTo(2);
        assertThat(completed.status()).isEqualTo(CredentialProvisioningOperationStatus.COMPLETED);
        assertThat(completed.failureCode()).isNull();
        assertThat(completed.failureDetail()).isNull();
    }

    private ServiceCredential provisioning(ServiceCredentialId id) {
        return ServiceCredential.provisioning(
                id,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "primary sandbox",
                ApplicationUserId.newId(),
                CredentialProvisioningOperationId.newId(),
                CREATED
        );
    }
}
