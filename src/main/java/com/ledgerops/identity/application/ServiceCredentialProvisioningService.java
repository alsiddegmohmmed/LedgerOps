package com.ledgerops.identity.application;

import com.ledgerops.identity.api.ServiceCredentialProvisioningPort;
import com.ledgerops.identity.api.ServiceCredentialProvisioningRequest;
import com.ledgerops.identity.api.ServiceCredentialProvisioningResult;
import com.ledgerops.identity.domain.ApplicationUserId;
import com.ledgerops.identity.domain.CredentialProvisioningOperation;
import com.ledgerops.identity.domain.CredentialProvisioningOperationId;
import com.ledgerops.identity.domain.CredentialProvisioningOperationRepository;
import com.ledgerops.identity.domain.ServiceCredential;
import com.ledgerops.identity.domain.ServiceCredentialId;
import com.ledgerops.identity.domain.ServiceCredentialRepository;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Coordinates the two consistency boundaries of credential provisioning.
 *
 * <p>Phase one commits durable Core state. The Keycloak call happens after
 * that transaction has ended. Phase two commits the local outcome and consumes
 * the one-time disclosure. This class deliberately has no Spring stereotype
 * until the concrete Keycloak adapter is installed.</p>
 */
final class ServiceCredentialProvisioningService implements ServiceCredentialProvisioningPort {

    private final ServiceCredentialRepository credentials;
    private final CredentialProvisioningOperationRepository operations;
    private final KeycloakCredentialProvisioner keycloak;
    private final TransactionTemplate transactions;
    private final Clock clock;

    ServiceCredentialProvisioningService(
            ServiceCredentialRepository credentials,
            CredentialProvisioningOperationRepository operations,
            KeycloakCredentialProvisioner keycloak,
            TransactionTemplate transactions,
            Clock clock
    ) {
        this.credentials = Objects.requireNonNull(credentials, "Credential repository must not be null");
        this.operations = Objects.requireNonNull(operations, "Operation repository must not be null");
        this.keycloak = Objects.requireNonNull(keycloak, "Keycloak provisioner must not be null");
        this.transactions = Objects.requireNonNull(transactions, "Transaction template must not be null");
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
    }

    @Override
    public ServiceCredentialProvisioningResult provision(ServiceCredentialProvisioningRequest request) {
        Objects.requireNonNull(request, "Provisioning request must not be null");
        PendingProvisioning pending = inTransaction(() -> createPending(request));
        return callKeycloakAndFinalize(pending);
    }

    @Override
    public ServiceCredentialProvisioningResult retry(UUID operationId) {
        Objects.requireNonNull(operationId, "Operation ID must not be null");
        PendingProvisioning pending = inTransaction(
                () -> prepareRetry(CredentialProvisioningOperationId.from(operationId)));
        return callKeycloakAndFinalize(pending);
    }

    private PendingProvisioning createPending(ServiceCredentialProvisioningRequest request) {
        Instant now = clock.instant();
        ServiceCredentialId credentialId = ServiceCredentialId.newId();
        CredentialProvisioningOperationId operationId = CredentialProvisioningOperationId.newId();
        ServiceCredential credential = ServiceCredential.provisioning(
                credentialId,
                request.tenantId(),
                request.merchantId(),
                request.label(),
                new ApplicationUserId(request.createdByApplicationUserId()),
                operationId,
                request.replacesCredentialId() == null
                        ? null : ServiceCredentialId.from(request.replacesCredentialId()),
                now
        );
        CredentialProvisioningOperation operation = CredentialProvisioningOperation.pending(
                operationId,
                credentialId,
                request.tenantId(),
                now
        );
        credentials.save(credential);
        operations.save(operation);
        return PendingProvisioning.from(credential, operation);
    }

    private PendingProvisioning prepareRetry(CredentialProvisioningOperationId operationId) {
        CredentialProvisioningOperation operation = operations.findByIdForUpdate(operationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Provisioning operation does not exist: " + operationId.value()));
        ServiceCredential credential = credentials.findByIdForUpdate(operation.credentialId())
                .orElseThrow(() -> new IllegalStateException(
                        "Credential for provisioning operation does not exist: "
                                + operation.credentialId().value()));
        if (!credential.provisioningOperationId().equals(operation.id())) {
            throw new IllegalStateException("Credential and provisioning operation are not linked");
        }
        if (operation.status() == com.ledgerops.identity.domain.CredentialProvisioningOperationStatus.PENDING
                && credential.status() == com.ledgerops.identity.domain.ServiceCredentialStatus.PROVISIONING) {
            return PendingProvisioning.from(credential, operation);
        }
        if (operation.status() != com.ledgerops.identity.domain.CredentialProvisioningOperationStatus.FAILED
                || credential.status() != com.ledgerops.identity.domain.ServiceCredentialStatus.FAILED) {
            throw new IllegalStateException(
                    "Only a failed operation or a pending crash-recovery operation can be retried");
        }
        Instant now = clock.instant();
        ServiceCredential retryingCredential = credential.retryProvisioning(now);
        CredentialProvisioningOperation retryingOperation = operation.retry(now);
        credentials.save(retryingCredential);
        operations.save(retryingOperation);
        return PendingProvisioning.from(retryingCredential, retryingOperation);
    }

    private ServiceCredentialProvisioningResult callKeycloakAndFinalize(PendingProvisioning pending) {
        KeycloakCredentialProvisioner.ProvisionedClient provisionedClient;
        try {
            provisionedClient = keycloak.provision(new KeycloakCredentialProvisioner.ProvisioningRequest(
                    pending.operation.id().value(),
                    pending.credential.keycloakClientId(),
                    pending.credential.label()
            ));
        } catch (KeycloakCredentialProvisioningException exception) {
            markFailed(pending, exception);
            throw new ServiceCredentialProvisioningFailedException(
                    pending.operation.id(),
                    exception.code(),
                    exception.getMessage(),
                    exception
            );
        }

        String clientSecret = provisionedClient.clientSecret();
        return inTransaction(() -> activateAndComplete(pending, clientSecret));
    }

    private void markFailed(
            PendingProvisioning pending,
            KeycloakCredentialProvisioningException failure
    ) {
        inTransaction(() -> {
            ServiceCredential credential = credentials.findByIdForUpdate(pending.credential.id())
                    .orElseThrow(() -> new IllegalStateException(
                            "Credential disappeared during provisioning failure handling"));
            CredentialProvisioningOperation operation = operations.findByIdForUpdate(pending.operation.id())
                    .orElseThrow(() -> new IllegalStateException(
                            "Provisioning operation disappeared during failure handling"));
            Instant now = clock.instant();
            credentials.save(credential.fail(now));
            operations.save(operation.recordFailure(
                    failure.code(), bounded(failure.getMessage()), now));
        });
    }

    private ServiceCredentialProvisioningResult activateAndComplete(
            PendingProvisioning pending,
            String clientSecret
    ) {
        ServiceCredential credential = credentials.findByIdForUpdate(pending.credential.id())
                .orElseThrow(() -> new IllegalStateException(
                        "Credential disappeared during activation"));
        CredentialProvisioningOperation operation = operations.findByIdForUpdate(pending.operation.id())
                .orElseThrow(() -> new IllegalStateException(
                        "Provisioning operation disappeared during activation"));
        if (!credential.provisioningOperationId().equals(operation.id())
                || !operation.credentialId().equals(credential.id())) {
            throw new IllegalStateException("Credential and provisioning operation are not linked");
        }
        Instant now = clock.instant();
        ServiceCredential active = credential.activate(now);
        CredentialProvisioningOperation completed = operation.complete(now);
        credentials.save(active);
        operations.save(completed);
        return new ServiceCredentialProvisioningResult(
                active.id().value(),
                completed.id().value(),
                active.tenantId(),
                active.merchantId(),
                active.keycloakClientId(),
                clientSecret
        );
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        T result = transactions.execute(status -> work.get());
        return Objects.requireNonNull(result, "Credential transaction returned no result");
    }

    private void inTransaction(Runnable work) {
        transactions.executeWithoutResult(status -> work.run());
    }

    private static String bounded(String detail) {
        String normalized = detail == null || detail.isBlank()
                ? "Keycloak provisioning failed"
                : detail.trim();
        return normalized.length() <= 1024 ? normalized : normalized.substring(0, 1024);
    }

    private record PendingProvisioning(
            ServiceCredential credential,
            CredentialProvisioningOperation operation
    ) {
        private PendingProvisioning {
            Objects.requireNonNull(credential, "Pending credential must not be null");
            Objects.requireNonNull(operation, "Pending operation must not be null");
        }

        static PendingProvisioning from(
                ServiceCredential credential,
                CredentialProvisioningOperation operation
        ) {
            return new PendingProvisioning(credential, operation);
        }
    }
}
