package com.ledgerops.identity.application;

import com.ledgerops.identity.api.ServiceCredentialRevocationPort;
import com.ledgerops.identity.api.ServiceCredentialRevocationResult;
import com.ledgerops.identity.api.ServiceCredentialRevocationFailedException;
import com.ledgerops.identity.domain.CredentialProvisioningOperation;
import com.ledgerops.identity.domain.CredentialProvisioningOperationId;
import com.ledgerops.identity.domain.CredentialProvisioningOperationRepository;
import com.ledgerops.identity.domain.ServiceCredential;
import com.ledgerops.identity.domain.ServiceCredentialId;
import com.ledgerops.identity.domain.ServiceCredentialRepository;
import com.ledgerops.identity.domain.ServiceCredentialStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Coordinates local-first service-credential revocation.
 *
 * <p>The local credential and its durable provisioning operation are changed
 * in one Core transaction. Only after that transaction commits does the
 * service ask Keycloak to disable the deterministic client. If cleanup fails,
 * the credential remains locally revoked and calling {@link #revoke(UUID)}
 * again retries only the external cleanup.</p>
 */
public final class ServiceCredentialRevocationService implements ServiceCredentialRevocationPort {

    private final ServiceCredentialRepository credentials;
    private final CredentialProvisioningOperationRepository operations;
    private final KeycloakCredentialDisabler keycloak;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ServiceCredentialRevocationService(
            ServiceCredentialRepository credentials,
            CredentialProvisioningOperationRepository operations,
            KeycloakCredentialDisabler keycloak,
            TransactionTemplate transactions,
            Clock clock
    ) {
        this.credentials = Objects.requireNonNull(credentials, "Credential repository must not be null");
        this.operations = Objects.requireNonNull(operations, "Operation repository must not be null");
        this.keycloak = Objects.requireNonNull(keycloak, "Keycloak disabler must not be null");
        this.transactions = Objects.requireNonNull(transactions, "Transaction template must not be null");
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
    }

    @Override
    public ServiceCredentialRevocationResult revoke(UUID credentialId) {
        Objects.requireNonNull(credentialId, "Credential ID must not be null");
        RevocationTarget target = inTransaction(
                () -> revokeLocally(ServiceCredentialId.from(credentialId)));

        try {
            keycloak.disable(new KeycloakCredentialDisabler.DisableRequest(
                    target.credential.id().value(),
                    target.credential.keycloakClientId()
            ));
        } catch (KeycloakCredentialProvisioningException exception) {
            throw new ServiceCredentialRevocationFailedException(
                    target.credential.id().value(),
                    exception.code(),
                    exception.getMessage(),
                    exception
            );
        }

        return new ServiceCredentialRevocationResult(
                target.credential.id().value(),
                target.operation.id().value(),
                target.credential.tenantId(),
                target.credential.merchantId(),
                target.credential.keycloakClientId()
        );
    }

    private RevocationTarget revokeLocally(ServiceCredentialId credentialId) {
        ServiceCredential credential = credentials.findByIdForUpdate(credentialId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Service credential does not exist: " + credentialId.value()));
        CredentialProvisioningOperation operation = operations.findByIdForUpdate(
                        credential.provisioningOperationId())
                .orElseThrow(() -> new IllegalStateException(
                        "Provisioning operation does not exist for credential: " + credentialId.value()));

        validateLinkage(credential, operation);

        boolean credentialRevoked = credential.status() == ServiceCredentialStatus.REVOKED;
        boolean operationRevoked = operation.status()
                == com.ledgerops.identity.domain.CredentialProvisioningOperationStatus.REVOKED;
        if (credentialRevoked != operationRevoked) {
            throw new IllegalStateException(
                    "Credential and provisioning operation have inconsistent revocation state");
        }

        if (!credentialRevoked) {
            Instant now = clock.instant();
            credentials.save(credential.revoke(now));
            operations.save(operation.revoke(now));
        }

        return new RevocationTarget(credential, operation);
    }

    private static void validateLinkage(
            ServiceCredential credential,
            CredentialProvisioningOperation operation
    ) {
        if (!credential.provisioningOperationId().equals(operation.id())
                || !operation.credentialId().equals(credential.id())
                || !operation.tenantId().equals(credential.tenantId())
                || !operation.keycloakClientId().equals(credential.keycloakClientId())) {
            throw new IllegalStateException(
                    "Credential and provisioning operation are not linked");
        }
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        T result = transactions.execute(status -> work.get());
        return Objects.requireNonNull(result, "Credential transaction returned no result");
    }

    private record RevocationTarget(
            ServiceCredential credential,
            CredentialProvisioningOperation operation
    ) {
        private RevocationTarget {
            Objects.requireNonNull(credential, "Revocation credential must not be null");
            Objects.requireNonNull(operation, "Revocation operation must not be null");
        }
    }
}
