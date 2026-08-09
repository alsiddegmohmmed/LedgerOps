package com.ledgerops.identity.infrastructure;

import com.ledgerops.identity.domain.ApplicationUserId;
import com.ledgerops.identity.domain.CredentialProvisioningOperation;
import com.ledgerops.identity.domain.CredentialProvisioningOperationId;
import com.ledgerops.identity.domain.ServiceCredential;
import com.ledgerops.identity.domain.ServiceCredentialId;
import com.ledgerops.identity.domain.ServiceCredentialStatus;
import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class ServiceCredentialPersistenceIntegrationTests {
    private static final Instant CREATED = Instant.parse("2026-02-01T10:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private ServiceCredentialPersistenceAdapter credentials;

    @Autowired
    private CredentialProvisioningOperationPersistenceAdapter operations;

    @Test
    void savesAndReloadsCredentialAndProvisioningOperationAsOneCoreTransaction() {
        UUID userId = UUID.randomUUID();
        insertApplicationUser(userId);
        ServiceCredentialId credentialId = ServiceCredentialId.newId();
        CredentialProvisioningOperationId operationId = CredentialProvisioningOperationId.newId();
        ServiceCredential credential = ServiceCredential.provisioning(
                credentialId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "primary sandbox",
                new ApplicationUserId(userId),
                operationId,
                CREATED
        );
        CredentialProvisioningOperation operation = CredentialProvisioningOperation.pending(
                operationId, credentialId, credential.tenantId(), CREATED);

        transactions.executeWithoutResult(status -> {
            credentials.save(credential);
            operations.save(operation);
        });

        assertThat(credentials.findById(credentialId)).hasValueSatisfying(loaded -> {
            assertThat(loaded.keycloakClientId()).isEqualTo(credential.keycloakClientId());
            assertThat(loaded.provisioningOperationId()).isEqualTo(operationId);
            assertThat(loaded.disclosureStatus().name()).isEqualTo("PENDING");
        });
        assertThat(operations.findById(operationId)).hasValueSatisfying(loaded -> {
            assertThat(loaded.credentialId()).isEqualTo(credentialId);
            assertThat(loaded.status().name()).isEqualTo("PENDING");
            assertThat(loaded.attemptCount()).isZero();
        });
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_schema = 'identity' AND table_name = 'service_credentials' "
                        + "AND column_name IN ('secret', 'client_secret', 'raw_secret')",
                Integer.class
        )).isZero();
    }

    @Test
    void readsCredentialsUsingDeterministicKeysetOrderAndFilters() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID merchantA = UUID.randomUUID();
        UUID merchantB = UUID.randomUUID();
        insertApplicationUser(userId);

        ServiceCredential newest = credential(
                "00000000-0000-0000-0000-000000000001",
                tenantId,
                merchantA,
                userId,
                Instant.parse("2026-02-01T12:00:00Z"));
        ServiceCredential sameTimestampHigherId = credential(
                "00000000-0000-0000-0000-000000000003",
                tenantId,
                merchantA,
                userId,
                Instant.parse("2026-02-01T11:00:00Z"));
        ServiceCredential sameTimestampLowerId = credential(
                "00000000-0000-0000-0000-000000000002",
                tenantId,
                merchantB,
                userId,
                Instant.parse("2026-02-01T11:00:00Z"));
        ServiceCredential oldest = credential(
                "00000000-0000-0000-0000-000000000004",
                tenantId,
                merchantB,
                userId,
                Instant.parse("2026-02-01T10:00:00Z"));

        transactions.executeWithoutResult(status -> {
            for (ServiceCredential credential : List.of(
                    newest, sameTimestampHigherId, sameTimestampLowerId, oldest)) {
                credentials.save(credential);
                operations.save(CredentialProvisioningOperation.pending(
                        credential.provisioningOperationId(),
                        credential.id(),
                        credential.tenantId(),
                        credential.createdAt()));
            }
        });

        List<ServiceCredential> firstPage = credentials.findPage(
                tenantId, null, null,
                null, null, 2);
        assertThat(firstPage).extracting(ServiceCredential::id)
                .containsExactly(newest.id(), sameTimestampHigherId.id());

        ServiceCredential position = firstPage.get(1);
        List<ServiceCredential> secondPage = credentials.findPage(
                tenantId, null, null,
                position.createdAt(), position.id(), 2);
        assertThat(secondPage).extracting(ServiceCredential::id)
                .containsExactly(sameTimestampLowerId.id(), oldest.id());

        List<ServiceCredential> merchantPage = credentials.findPage(
                tenantId, merchantA, ServiceCredentialStatus.PROVISIONING,
                null, null, 10);
        assertThat(merchantPage).extracting(ServiceCredential::id)
                .containsExactly(newest.id(), sameTimestampHigherId.id());
    }

    private ServiceCredential credential(
            String credentialId,
            UUID tenantId,
            UUID merchantId,
            UUID userId,
            Instant createdAt
    ) {
        return ServiceCredential.provisioning(
                ServiceCredentialId.from(UUID.fromString(credentialId)),
                tenantId,
                merchantId,
                "credential-" + credentialId,
                new ApplicationUserId(userId),
                CredentialProvisioningOperationId.from(UUID.fromString(
                        "10000000-0000-0000-0000-" + credentialId.substring(24))),
                createdAt
        );
    }

    private void insertApplicationUser(UUID userId) {
        Timestamp now = Timestamp.from(CREATED);
        jdbc.update(
                """
                INSERT INTO identity.application_users (
                    id, issuer, subject, status, version, created_at, updated_at
                ) VALUES (?, ?, ?, 'ACTIVE', 0, ?, ?)
                """,
                userId, "issuer-" + userId, "subject-" + userId, now, now
        );
    }
}
