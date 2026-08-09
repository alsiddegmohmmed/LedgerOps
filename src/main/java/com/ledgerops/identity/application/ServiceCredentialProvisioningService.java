package com.ledgerops.identity.application;

import com.ledgerops.identity.api.ServiceCredentialProvisioningPort;
import com.ledgerops.identity.api.ServiceCredentialProvisioningRequest;
import com.ledgerops.identity.api.ServiceCredentialProvisioningResult;
import com.ledgerops.identity.api.ServiceCredentialProvisioningFailedException;
import com.ledgerops.identity.api.ServiceCredentialRotationFailedException;
import com.ledgerops.identity.domain.ApplicationUserId;
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
 * Coordinates the two consistency boundaries of credential provisioning.
 *
 * <p>Phase one commits durable Core state. The Keycloak call happens after
 * that transaction has ended. Phase two commits the local outcome and consumes
 * the one-time disclosure. Infrastructure configuration registers this service
 * only when the concrete Keycloak adapter is enabled.</p>
 */
public final class ServiceCredentialProvisioningService implements ServiceCredentialProvisioningPort {

    private final ServiceCredentialRepository credentials;
    private final CredentialProvisioningOperationRepository operations;
    private final KeycloakCredentialProvisioner keycloak;
    private final KeycloakCredentialDisabler disabler;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ServiceCredentialProvisioningService(
            ServiceCredentialRepository credentials,
            CredentialProvisioningOperationRepository operations,
            KeycloakCredentialProvisioner keycloak,
            KeycloakCredentialDisabler disabler,
            TransactionTemplate transactions,
            Clock clock
    ) {
        this.credentials = Objects.requireNonNull(credentials, "Credential repository must not be null");
        this.operations = Objects.requireNonNull(operations, "Operation repository must not be null");
        this.keycloak = Objects.requireNonNull(keycloak, "Keycloak provisioner must not be null");
        this.disabler = Objects.requireNonNull(disabler, "Keycloak disabler must not be null");
        this.transactions = Objects.requireNonNull(transactions, "Transaction template must not be null");
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
    }

    @Override
    public ServiceCredentialProvisioningResult provision(ServiceCredentialProvisioningRequest request) {
        Objects.requireNonNull(request, "Provisioning request must not be null");
        PendingProvisioning pending = inTransaction(() -> request.replacesCredentialId() == null
                ? createPending(request)
                : createRotationPending(ServiceCredentialId.from(request.replacesCredentialId())));
        return callKeycloakAndFinalize(pending);
    }

    @Override
    public ServiceCredentialProvisioningResult retry(UUID operationId) {
        Objects.requireNonNull(operationId, "Operation ID must not be null");
        PendingProvisioning pending = inTransaction(
                () -> prepareRetry(CredentialProvisioningOperationId.from(operationId)));
        return callKeycloakAndFinalize(pending);
    }

    @Override
    public ServiceCredentialProvisioningResult rotate(UUID credentialId) {
        Objects.requireNonNull(credentialId, "Credential ID must not be null");
        PendingProvisioning pending = inTransaction(
                () -> createRotationPending(ServiceCredentialId.from(credentialId)));
        return callKeycloakAndFinalize(pending);
    }

    @Override
    public void retryRotationCleanup(UUID replacementCredentialId) {
        Objects.requireNonNull(replacementCredentialId, "Replacement credential ID must not be null");
        RotationCleanupTarget target = inTransaction(
                () -> prepareRotationCleanup(ServiceCredentialId.from(replacementCredentialId)));
        disableOldClient(target, ServiceCredentialId.from(replacementCredentialId));
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
                null,
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

    private PendingProvisioning createRotationPending(ServiceCredentialId oldCredentialId) {
        ServiceCredential oldCredential = credentials.findByIdForUpdate(oldCredentialId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Service credential does not exist: " + oldCredentialId.value()));
        if (oldCredential.status() != ServiceCredentialStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Only an active credential can be rotated: " + oldCredentialId.value());
        }

        Instant now = clock.instant();
        ServiceCredentialId replacementId = ServiceCredentialId.newId();
        CredentialProvisioningOperationId operationId = CredentialProvisioningOperationId.newId();
        ServiceCredential replacement = ServiceCredential.provisioning(
                replacementId,
                oldCredential.tenantId(),
                oldCredential.merchantId(),
                oldCredential.label(),
                oldCredential.createdBy(),
                operationId,
                oldCredential.id(),
                now
        );
        CredentialProvisioningOperation operation = CredentialProvisioningOperation.pending(
                operationId,
                replacementId,
                oldCredential.tenantId(),
                now
        );
        credentials.save(replacement);
        operations.save(operation);
        return PendingProvisioning.from(replacement, operation);
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
                    pending.operation.id().value(),
                    exception.code(),
                    exception.getMessage(),
                    exception
            );
        }

        String clientSecret = provisionedClient.clientSecret();
        FinalizedProvisioning finalized = inTransaction(
                () -> activateAndComplete(pending, clientSecret));
        if (finalized.cleanupTarget() != null) {
            disableOldClient(finalized.cleanupTarget(), pending.credential.id());
        }
        return finalized.result();
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

    private FinalizedProvisioning activateAndComplete(
            PendingProvisioning pending,
            String clientSecret
    ) {
        if (pending.credential.replacesCredentialId() != null) {
            return activateReplacementAndComplete(pending, clientSecret);
        }
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
        return new FinalizedProvisioning(
                result(active, completed, clientSecret),
                null
        );
    }

    private FinalizedProvisioning activateReplacementAndComplete(
            PendingProvisioning pending,
            String clientSecret
    ) {
        ServiceCredentialId oldCredentialId = pending.credential.replacesCredentialId();
        ServiceCredential oldCredential = credentials.findByIdForUpdate(oldCredentialId)
                .orElseThrow(() -> new IllegalStateException(
                        "Old credential for rotation does not exist: " + oldCredentialId.value()));
        ServiceCredential replacement = credentials.findByIdForUpdate(pending.credential.id())
                .orElseThrow(() -> new IllegalStateException(
                        "Replacement credential disappeared during activation"));
        CredentialProvisioningOperation operation = operations.findByIdForUpdate(
                        pending.operation.id())
                .orElseThrow(() -> new IllegalStateException(
                        "Replacement operation disappeared during activation"));
        if (!replacement.provisioningOperationId().equals(operation.id())
                || !operation.credentialId().equals(replacement.id())
                || !operation.tenantId().equals(replacement.tenantId())
                || !operation.keycloakClientId().equals(replacement.keycloakClientId())) {
            throw new IllegalStateException("Replacement credential and operation are not linked");
        }
        if (oldCredential.status() != ServiceCredentialStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Old credential is no longer active; rotation cannot complete: "
                            + oldCredential.id().value());
        }

        Instant now = clock.instant();
        ServiceCredential activeReplacement = replacement.activate(now);
        ServiceCredential revokedOld = oldCredential.revoke(now);
        CredentialProvisioningOperation completed = operation.complete(now);
        credentials.save(activeReplacement);
        credentials.save(revokedOld);
        operations.save(completed);
        return new FinalizedProvisioning(
                result(activeReplacement, completed, clientSecret),
                new RotationCleanupTarget(revokedOld)
        );
    }

    private RotationCleanupTarget prepareRotationCleanup(ServiceCredentialId replacementCredentialId) {
        ServiceCredential replacement = credentials.findByIdForUpdate(replacementCredentialId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Replacement credential does not exist: " + replacementCredentialId.value()));
        ServiceCredentialId oldCredentialId = replacement.replacesCredentialId();
        if (oldCredentialId == null || replacement.status() != ServiceCredentialStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Only an active replacement credential can retry rotation cleanup");
        }
        ServiceCredential oldCredential = credentials.findByIdForUpdate(oldCredentialId)
                .orElseThrow(() -> new IllegalStateException(
                        "Old credential for rotation does not exist: " + oldCredentialId.value()));
        if (oldCredential.status() != ServiceCredentialStatus.REVOKED) {
            throw new IllegalStateException(
                    "Rotation cleanup requires the old credential to be locally revoked");
        }
        return new RotationCleanupTarget(oldCredential);
    }

    private void disableOldClient(RotationCleanupTarget target, ServiceCredentialId replacementCredentialId) {
        try {
            disabler.disable(new KeycloakCredentialDisabler.DisableRequest(
                    target.oldCredential().id().value(),
                    target.oldCredential().keycloakClientId()
            ));
        } catch (KeycloakCredentialProvisioningException exception) {
            throw new ServiceCredentialRotationFailedException(
                    replacementCredentialId.value(),
                    target.oldCredential().id().value(),
                    exception.code(),
                    exception.getMessage(),
                    exception
            );
        }
    }

    private static ServiceCredentialProvisioningResult result(
            ServiceCredential credential,
            CredentialProvisioningOperation operation,
            String clientSecret
    ) {
        return new ServiceCredentialProvisioningResult(
                credential.id().value(),
                operation.id().value(),
                credential.tenantId(),
                credential.merchantId(),
                credential.keycloakClientId(),
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

    private record FinalizedProvisioning(
            ServiceCredentialProvisioningResult result,
            RotationCleanupTarget cleanupTarget
    ) {
        private FinalizedProvisioning {
            Objects.requireNonNull(result, "Finalized provisioning result must not be null");
        }
    }

    private record RotationCleanupTarget(ServiceCredential oldCredential) {
        private RotationCleanupTarget {
            Objects.requireNonNull(oldCredential, "Old rotation credential must not be null");
        }
    }
}
