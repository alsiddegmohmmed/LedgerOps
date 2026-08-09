package com.ledgerops.identity.application;

import com.ledgerops.identity.api.ServiceCredentialProvisioningResult;
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
class ServiceCredentialRotationIntegrationTests {

    private static final Instant NOW = Instant.parse("2026-02-04T10:00:00Z");

    @Autowired
    private ServiceCredentialRepository credentials;

    @Autowired
    private CredentialProvisioningOperationRepository operations;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void provisionsReplacementThenAtomicallyActivatesItAndRevokesTheOldCredential() {
        ActiveCredential old = insertActiveCredential();
        RecordingKeycloak keycloak = new RecordingKeycloak(credentials);

        ServiceCredentialProvisioningResult result = service(keycloak).rotate(old.credential.id().value());

        ServiceCredential replacement = credentials.findById(
                        ServiceCredentialId.from(result.credentialId()))
                .orElseThrow();
        assertThat(replacement.status()).isEqualTo(ServiceCredentialStatus.ACTIVE);
        assertThat(replacement.replacesCredentialId()).isEqualTo(old.credential.id());
        assertThat(credentials.findById(old.credential.id()))
                .hasValueSatisfying(credential ->
                        assertThat(credential.status()).isEqualTo(ServiceCredentialStatus.REVOKED));
        assertThat(operations.findById(CredentialProvisioningOperationId.from(result.operationId())))
                .hasValueSatisfying(operation ->
                        assertThat(operation.status()).isEqualTo(CredentialProvisioningOperationStatus.COMPLETED));
        assertThat(result.clientSecret()).isEqualTo("replacement-secret");
        assertThat(keycloak.provisionCalls).hasSize(1);
        assertThat(keycloak.disableCalls).hasSize(1);
        assertThat(keycloak.provisionTransactionWasActive).containsExactly(false);
        assertThat(keycloak.disableTransactionWasActive).containsExactly(false);
        assertThat(keycloak.oldStatusAtDisable).isEqualTo(ServiceCredentialStatus.REVOKED);
        assertThat(keycloak.disableCalls.get(0).credentialId()).isEqualTo(old.credential.id().value());
    }

    @Test
    void retriesAFailedReplacementOperationUsingTheSameDeterministicClient() {
        ActiveCredential old = insertActiveCredential();
        RecordingKeycloak keycloak = new RecordingKeycloak(credentials);
        keycloak.provisionFailure = new KeycloakCredentialProvisioningException(
                "KEYCLOAK_TIMEOUT", "Keycloak administration timed out");
        ServiceCredentialProvisioningService service = service(keycloak);
        ServiceCredentialProvisioningFailedException[] captured =
                new ServiceCredentialProvisioningFailedException[1];

        assertThatThrownBy(() -> service.rotate(old.credential.id().value()))
                .isInstanceOf(ServiceCredentialProvisioningFailedException.class)
                .satisfies(thrown -> captured[0] = (ServiceCredentialProvisioningFailedException) thrown);

        UUID operationId = captured[0].operationId().value();
        UUID replacementId = jdbc.queryForObject(
                "SELECT id FROM identity.service_credentials WHERE provisioning_operation_id = ?",
                UUID.class,
                operationId);
        assertThat(credentials.findById(ServiceCredentialId.from(replacementId)))
                .hasValueSatisfying(credential ->
                        assertThat(credential.status()).isEqualTo(ServiceCredentialStatus.FAILED));
        assertThat(credentials.findById(old.credential.id()))
                .hasValueSatisfying(credential ->
                        assertThat(credential.status()).isEqualTo(ServiceCredentialStatus.ACTIVE));

        keycloak.provisionFailure = null;
        ServiceCredentialProvisioningResult result = service.retry(operationId);

        assertThat(result.credentialId()).isEqualTo(replacementId);
        assertThat(keycloak.provisionCalls).hasSize(2);
        assertThat(keycloak.provisionCalls.get(0).keycloakClientId())
                .isEqualTo(keycloak.provisionCalls.get(1).keycloakClientId());
        assertThat(credentials.findById(old.credential.id()))
                .hasValueSatisfying(credential ->
                        assertThat(credential.status()).isEqualTo(ServiceCredentialStatus.REVOKED));
        assertThat(keycloak.disableCalls).hasSize(1);
    }

    @Test
    void keepsAtomicLocalRotationWhenOldClientCleanupFailsAndSupportsCleanupRetry() {
        ActiveCredential old = insertActiveCredential();
        RecordingKeycloak keycloak = new RecordingKeycloak(credentials);
        keycloak.disableFailure = new KeycloakCredentialProvisioningException(
                "KEYCLOAK_UNAVAILABLE", "Keycloak administration is unavailable");
        ServiceCredentialProvisioningService service = service(keycloak);
        ServiceCredentialRotationFailedException[] captured =
                new ServiceCredentialRotationFailedException[1];

        assertThatThrownBy(() -> service.rotate(old.credential.id().value()))
                .isInstanceOf(ServiceCredentialRotationFailedException.class)
                .satisfies(thrown -> {
                    captured[0] = (ServiceCredentialRotationFailedException) thrown;
                    assertThat(captured[0].oldCredentialId()).isEqualTo(old.credential.id());
                    assertThat(captured[0].failureCode()).isEqualTo("KEYCLOAK_UNAVAILABLE");
                });

        UUID replacementId = captured[0].replacementCredentialId().value();
        assertThat(credentials.findById(ServiceCredentialId.from(replacementId)))
                .hasValueSatisfying(credential -> {
                    assertThat(credential.status()).isEqualTo(ServiceCredentialStatus.ACTIVE);
                    assertThat(credential.replacesCredentialId()).isEqualTo(old.credential.id());
                });
        assertThat(credentials.findById(old.credential.id()))
                .hasValueSatisfying(credential ->
                        assertThat(credential.status()).isEqualTo(ServiceCredentialStatus.REVOKED));
        assertThat(keycloak.oldStatusAtDisable).isEqualTo(ServiceCredentialStatus.REVOKED);

        keycloak.disableFailure = null;
        service.retryRotationCleanup(replacementId);

        assertThat(keycloak.disableCalls).hasSize(2);
        assertThat(keycloak.disableCalls.get(0).credentialId())
                .isEqualTo(keycloak.disableCalls.get(1).credentialId());
    }

    @Test
    void refusesToRotateARevokedCredential() {
        ActiveCredential old = insertActiveCredential();
        transactions.executeWithoutResult(status -> {
            ServiceCredential revoked = credentials.findByIdForUpdate(old.credential.id())
                    .orElseThrow()
                    .revoke(NOW.plusSeconds(1));
            credentials.save(revoked);
        });
        RecordingKeycloak keycloak = new RecordingKeycloak(credentials);

        assertThatThrownBy(() -> service(keycloak).rotate(old.credential.id().value()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only an active credential can be rotated");
        assertThat(keycloak.provisionCalls).isEmpty();
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
                "rotation integration",
                new ApplicationUserId(userId),
                operationId,
                NOW
        );
        CredentialProvisioningOperation pending = CredentialProvisioningOperation.pending(
                operationId, credentialId, tenantId, NOW);
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

    private static final class RecordingKeycloak
            implements KeycloakCredentialProvisioner, KeycloakCredentialDisabler {
        private final ServiceCredentialRepository credentials;
        private final List<ProvisioningRequest> provisionCalls = new ArrayList<>();
        private final List<DisableRequest> disableCalls = new ArrayList<>();
        private final List<Boolean> provisionTransactionWasActive = new ArrayList<>();
        private final List<Boolean> disableTransactionWasActive = new ArrayList<>();
        private ServiceCredentialStatus oldStatusAtDisable;
        private KeycloakCredentialProvisioningException provisionFailure;
        private KeycloakCredentialProvisioningException disableFailure;

        private RecordingKeycloak(ServiceCredentialRepository credentials) {
            this.credentials = credentials;
        }

        @Override
        public ProvisionedClient provision(ProvisioningRequest request) {
            provisionCalls.add(request);
            provisionTransactionWasActive.add(TransactionSynchronizationManager.isActualTransactionActive());
            if (provisionFailure != null) {
                throw provisionFailure;
            }
            return new ProvisionedClient("replacement-secret");
        }

        @Override
        public void disable(DisableRequest request) {
            disableCalls.add(request);
            disableTransactionWasActive.add(TransactionSynchronizationManager.isActualTransactionActive());
            oldStatusAtDisable = credentials.findById(ServiceCredentialId.from(request.credentialId()))
                    .orElseThrow()
                    .status();
            if (disableFailure != null) {
                throw disableFailure;
            }
        }
    }
}
