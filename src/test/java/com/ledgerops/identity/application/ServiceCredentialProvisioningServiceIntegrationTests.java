package com.ledgerops.identity.application;

import com.ledgerops.identity.api.ServiceCredentialProvisioningRequest;
import com.ledgerops.identity.api.ServiceCredentialProvisioningResult;
import com.ledgerops.identity.domain.CredentialProvisioningOperationRepository;
import com.ledgerops.identity.domain.CredentialProvisioningOperation;
import com.ledgerops.identity.domain.CredentialProvisioningOperationId;
import com.ledgerops.identity.domain.CredentialProvisioningOperationStatus;
import com.ledgerops.identity.domain.ServiceCredential;
import com.ledgerops.identity.domain.ServiceCredentialId;
import com.ledgerops.identity.domain.ServiceCredentialRepository;
import com.ledgerops.identity.domain.ServiceCredentialStatus;
import com.ledgerops.identity.domain.ApplicationUserId;
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
class ServiceCredentialProvisioningServiceIntegrationTests {

    private static final Instant NOW = Instant.parse("2026-02-02T10:00:00Z");

    @Autowired
    private ServiceCredentialRepository credentials;

    @Autowired
    private CredentialProvisioningOperationRepository operations;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void commitsCoreStateBeforeCallingKeycloakAndConsumesSecretOnlyOnActivation() {
        UUID userId = insertApplicationUser();
        RecordingKeycloak keycloak = new RecordingKeycloak();
        ServiceCredentialProvisioningService service = service(keycloak);

        ServiceCredentialProvisioningResult result = service.provision(
                request(userId));

        assertThat(keycloak.calls).hasSize(1);
        assertThat(keycloak.transactionWasActive).containsExactly(false);
        assertThat(result.clientSecret()).isEqualTo("secret-returned-once");
        assertThat(credentials.findById(com.ledgerops.identity.domain.ServiceCredentialId.from(
                result.credentialId()))).hasValueSatisfying(credential -> {
            assertThat(credential.status()).isEqualTo(ServiceCredentialStatus.ACTIVE);
            assertThat(credential.disclosureStatus().name()).isEqualTo("CONSUMED");
            assertThat(credential.disclosureConsumedAt()).isNotNull();
        });
        assertThat(operations.findById(
                com.ledgerops.identity.domain.CredentialProvisioningOperationId.from(result.operationId())))
                .hasValueSatisfying(operation -> {
                    assertThat(operation.status()).isEqualTo(CredentialProvisioningOperationStatus.COMPLETED);
                    assertThat(operation.attemptCount()).isZero();
                });
    }

    @Test
    void recordsRecoverableFailureWithoutPersistingTheKeycloakSecret() {
        UUID userId = insertApplicationUser();
        RecordingKeycloak keycloak = new RecordingKeycloak();
        keycloak.failure = new KeycloakCredentialProvisioningException(
                "KEYCLOAK_UNAVAILABLE", "Keycloak administration is unavailable");
        ServiceCredentialProvisioningService service = service(keycloak);

        ServiceCredentialProvisioningFailedException[] capturedFailure = new ServiceCredentialProvisioningFailedException[1];
        assertThatThrownBy(() -> service.provision(request(userId)))
                .isInstanceOf(ServiceCredentialProvisioningFailedException.class)
                .satisfies(thrown -> {
                    ServiceCredentialProvisioningFailedException failure =
                            (ServiceCredentialProvisioningFailedException) thrown;
                    capturedFailure[0] = failure;
                    assertThat(failure.failureCode()).isEqualTo("KEYCLOAK_UNAVAILABLE");
                });

        assertThat(keycloak.transactionWasActive).containsExactly(false);
        UUID operationId = capturedFailure[0].operationId().value();
        assertThat(operations.findById(
                com.ledgerops.identity.domain.CredentialProvisioningOperationId.from(operationId)))
                .hasValueSatisfying(operation -> {
                    assertThat(operation.status()).isEqualTo(CredentialProvisioningOperationStatus.FAILED);
                    assertThat(operation.failureCode()).isEqualTo("KEYCLOAK_UNAVAILABLE");
                    assertThat(operation.failureDetail()).doesNotContain("secret-returned-once");
                });
    }

    @Test
    void retriesTheSameDurableOperationAndDeterministicClientAfterFailure() {
        UUID userId = insertApplicationUser();
        RecordingKeycloak keycloak = new RecordingKeycloak();
        keycloak.failure = new KeycloakCredentialProvisioningException(
                "KEYCLOAK_TIMEOUT", "Keycloak administration timed out");
        ServiceCredentialProvisioningService service = service(keycloak);

        ServiceCredentialProvisioningFailedException[] capturedFailure = new ServiceCredentialProvisioningFailedException[1];
        assertThatThrownBy(() -> service.provision(request(userId)))
                .isInstanceOf(ServiceCredentialProvisioningFailedException.class)
                .satisfies(thrown -> capturedFailure[0] =
                        (ServiceCredentialProvisioningFailedException) thrown);

        keycloak.failure = null;
        ServiceCredentialProvisioningResult result = service.retry(capturedFailure[0].operationId().value());

        assertThat(keycloak.calls).hasSize(2);
        assertThat(keycloak.calls.get(0).operationId())
                .isEqualTo(keycloak.calls.get(1).operationId());
        assertThat(keycloak.calls.get(0).keycloakClientId())
                .isEqualTo(keycloak.calls.get(1).keycloakClientId());
        assertThat(keycloak.transactionWasActive).containsExactly(false, false);
        assertThat(result.clientSecret()).isEqualTo("secret-returned-once");
        assertThat(operations.findById(
                com.ledgerops.identity.domain.CredentialProvisioningOperationId.from(result.operationId())))
                .hasValueSatisfying(operation -> {
                    assertThat(operation.status()).isEqualTo(CredentialProvisioningOperationStatus.COMPLETED);
                    assertThat(operation.attemptCount()).isEqualTo(2);
        });
    }

    @Test
    void resumesPendingOperationAfterCrashBeforeLocalActivation() {
        UUID userId = insertApplicationUser();
        ServiceCredentialId credentialId = ServiceCredentialId.newId();
        CredentialProvisioningOperationId operationId = CredentialProvisioningOperationId.newId();
        UUID tenantId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        ServiceCredential credential = ServiceCredential.provisioning(
                credentialId,
                tenantId,
                merchantId,
                "crash recovery",
                new ApplicationUserId(userId),
                operationId,
                NOW
        );
        CredentialProvisioningOperation operation = CredentialProvisioningOperation.pending(
                operationId, credentialId, tenantId, NOW);
        transactions.executeWithoutResult(status -> {
            credentials.save(credential);
            operations.save(operation);
        });

        RecordingKeycloak keycloak = new RecordingKeycloak();
        ServiceCredentialProvisioningResult result = service(keycloak).retry(operationId.value());

        assertThat(keycloak.calls).hasSize(1);
        assertThat(keycloak.calls.get(0).operationId()).isEqualTo(operationId.value());
        assertThat(result.credentialId()).isEqualTo(credentialId.value());
        assertThat(operations.findById(operationId)).hasValueSatisfying(loaded ->
                assertThat(loaded.status()).isEqualTo(CredentialProvisioningOperationStatus.COMPLETED));
    }

    private ServiceCredentialProvisioningService service(RecordingKeycloak keycloak) {
        return new ServiceCredentialProvisioningService(
                credentials,
                operations,
                keycloak,
                keycloak,
                transactions,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private ServiceCredentialProvisioningRequest request(UUID userId) {
        return new ServiceCredentialProvisioningRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "sandbox integration",
                userId,
                null
        );
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

    private static final class RecordingKeycloak
            implements KeycloakCredentialProvisioner, KeycloakCredentialDisabler {
        private final List<ProvisioningRequest> calls = new ArrayList<>();
        private final List<Boolean> transactionWasActive = new ArrayList<>();
        private KeycloakCredentialProvisioningException failure;

        @Override
        public ProvisionedClient provision(ProvisioningRequest request) {
            calls.add(request);
            transactionWasActive.add(TransactionSynchronizationManager.isActualTransactionActive());
            if (failure != null) {
                throw failure;
            }
            return new ProvisionedClient("secret-returned-once");
        }

        @Override
        public void disable(DisableRequest request) {
            // Rotation is covered by ServiceCredentialRotationIntegrationTests.
        }
    }
}
