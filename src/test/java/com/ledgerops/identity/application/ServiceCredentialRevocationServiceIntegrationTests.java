package com.ledgerops.identity.application;

import com.ledgerops.identity.api.ServiceCredentialRevocationResult;
import com.ledgerops.identity.domain.ApplicationUserId;
import com.ledgerops.identity.domain.CredentialProvisioningOperation;
import com.ledgerops.identity.domain.CredentialProvisioningOperationId;
import com.ledgerops.identity.domain.CredentialProvisioningOperationRepository;
import com.ledgerops.identity.domain.CredentialProvisioningOperationStatus;
import com.ledgerops.identity.domain.ServiceCredential;
import com.ledgerops.identity.domain.ServiceCredentialId;
import com.ledgerops.identity.domain.ServiceCredentialRepository;
import com.ledgerops.identity.domain.ServiceCredentialStatus;
import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class ServiceCredentialRevocationServiceIntegrationTests {

    private static final Instant NOW = Instant.parse("2026-02-03T10:00:00Z");

    @Autowired
    private ServiceCredentialRepository credentials;

    @Autowired
    private CredentialProvisioningOperationRepository operations;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void commitsLocalRevocationBeforeCallingKeycloakCleanup() {
        ActiveCredential active = insertActiveCredential();
        RecordingDisabler disabler = new RecordingDisabler(credentials);

        ServiceCredentialRevocationResult result = service(disabler).revoke(active.credential.id().value());

        assertThat(result.credentialId()).isEqualTo(active.credential.id().value());
        assertThat(result.operationId()).isEqualTo(active.operation.id().value());
        assertThat(disabler.calls).hasSize(1);
        assertThat(disabler.transactionWasActive).containsExactly(false);
        assertThat(disabler.statusAtCall).isEqualTo(ServiceCredentialStatus.REVOKED);
        assertThat(credentials.findById(active.credential.id()))
                .hasValueSatisfying(credential ->
                        assertThat(credential.status()).isEqualTo(ServiceCredentialStatus.REVOKED));
        assertThat(operations.findById(active.operation.id()))
                .hasValueSatisfying(operation ->
                        assertThat(operation.status()).isEqualTo(CredentialProvisioningOperationStatus.REVOKED));
    }

    @Test
    void keepsLocalRevocationWhenKeycloakCleanupFailsAndRetriesCleanupIdempotently() {
        ActiveCredential active = insertActiveCredential();
        RecordingDisabler disabler = new RecordingDisabler(credentials);
        disabler.failure = new KeycloakCredentialProvisioningException(
                "KEYCLOAK_UNAVAILABLE", "Keycloak administration is unavailable");
        ServiceCredentialRevocationService service = service(disabler);

        assertThatThrownBy(() -> service.revoke(active.credential.id().value()))
                .isInstanceOf(ServiceCredentialRevocationFailedException.class)
                .satisfies(thrown -> {
                    ServiceCredentialRevocationFailedException failure =
                            (ServiceCredentialRevocationFailedException) thrown;
                    assertThat(failure.credentialId()).isEqualTo(active.credential.id());
                    assertThat(failure.failureCode()).isEqualTo("KEYCLOAK_UNAVAILABLE");
                });

        assertThat(credentials.findById(active.credential.id()))
                .hasValueSatisfying(credential ->
                        assertThat(credential.status()).isEqualTo(ServiceCredentialStatus.REVOKED));
        assertThat(operations.findById(active.operation.id()))
                .hasValueSatisfying(operation ->
                        assertThat(operation.status()).isEqualTo(CredentialProvisioningOperationStatus.REVOKED));

        disabler.failure = null;
        ServiceCredentialRevocationResult retry = service.revoke(active.credential.id().value());

        assertThat(retry.credentialId()).isEqualTo(active.credential.id().value());
        assertThat(disabler.calls).hasSize(2);
        assertThat(disabler.calls.get(0).keycloakClientId())
                .isEqualTo(disabler.calls.get(1).keycloakClientId());
        assertThat(disabler.statusAtCall).isEqualTo(ServiceCredentialStatus.REVOKED);
    }

    @Test
    void rejectsMissingCredentialBeforeAttemptingExternalCleanup() {
        RecordingDisabler disabler = new RecordingDisabler(credentials);

        assertThatThrownBy(() -> service(disabler).revoke(UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Service credential does not exist:");

        assertThat(disabler.calls).isEmpty();
    }

    private ServiceCredentialRevocationService service(RecordingDisabler disabler) {
        return new ServiceCredentialRevocationService(
                credentials,
                operations,
                disabler,
                transactions,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private ActiveCredential insertActiveCredential() {
        UUID userId = insertApplicationUser();
        UUID tenantId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        ServiceCredentialId credentialId = ServiceCredentialId.newId();
        CredentialProvisioningOperationId operationId = CredentialProvisioningOperationId.newId();

        ServiceCredential provisioning = ServiceCredential.provisioning(
                credentialId,
                tenantId,
                merchantId,
                "revocation integration",
                new ApplicationUserId(userId),
                operationId,
                NOW
        );
        CredentialProvisioningOperation pending = CredentialProvisioningOperation.pending(
                operationId,
                credentialId,
                tenantId,
                NOW
        );
        ServiceCredential active = provisioning.activate(NOW.plusSeconds(1));
        CredentialProvisioningOperation completed = pending.complete(NOW.plusSeconds(1));

        transactions.executeWithoutResult(status -> {
            credentials.save(active);
            operations.save(completed);
        });
        return new ActiveCredential(active, completed);
    }

    private UUID insertApplicationUser() {
        UUID userId = UUID.randomUUID();
        Timestamp now = Timestamp.from(NOW);
        jdbc.update(
                """
                INSERT INTO identity.application_users (
                    id, issuer, subject, status, version, created_at, updated_at
                ) VALUES (?, ?, ?, 'ACTIVE', 0, ?, ?)
                """,
                userId, "issuer-" + userId, "subject-" + userId, now, now
        );
        return userId;
    }

    private record ActiveCredential(
            ServiceCredential credential,
            CredentialProvisioningOperation operation
    ) {
    }

    private static final class RecordingDisabler implements KeycloakCredentialDisabler {
        private final ServiceCredentialRepository credentials;
        private final List<DisableRequest> calls = new ArrayList<>();
        private final List<Boolean> transactionWasActive = new ArrayList<>();
        private ServiceCredentialStatus statusAtCall;
        private KeycloakCredentialProvisioningException failure;

        private RecordingDisabler(ServiceCredentialRepository credentials) {
            this.credentials = credentials;
        }

        @Override
        public void disable(DisableRequest request) {
            calls.add(request);
            transactionWasActive.add(TransactionSynchronizationManager.isActualTransactionActive());
            statusAtCall = credentials.findById(ServiceCredentialId.from(request.credentialId()))
                    .orElseThrow()
                    .status();
            if (failure != null) {
                throw failure;
            }
        }
    }
}
