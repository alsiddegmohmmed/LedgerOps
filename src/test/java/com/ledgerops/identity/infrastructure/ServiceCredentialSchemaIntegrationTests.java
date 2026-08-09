package com.ledgerops.identity.infrastructure;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class ServiceCredentialSchemaIntegrationTests {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Test
    void evolvesV18WithoutASecretColumnAndAddsDurableOperationEvidence() {
        assertThat(tableExists("service_credentials")).isTrue();
        assertThat(tableExists("service_credential_provisioning_operations")).isTrue();

        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                  FROM information_schema.columns
                 WHERE table_schema = 'identity'
                   AND table_name IN ('service_credentials', 'service_credential_provisioning_operations')
                   AND column_name IN ('secret', 'client_secret', 'raw_secret')
                """,
                Integer.class
        )).isZero();

        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                  FROM information_schema.columns
                 WHERE table_schema = 'identity'
                   AND table_name = 'service_credentials'
                   AND column_name IN (
                       'label', 'replaces_credential_id', 'provisioning_operation_id',
                       'disclosure_status', 'disclosure_consumed_at'
                   )
                """,
                Integer.class
        )).isEqualTo(5);
    }

    @Test
    void persistsProvisioningAndFailureEvidenceWithoutAcceptingUnknownStates() {
        UUID userId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        insertApplicationUser(userId, now);

        transactions.executeWithoutResult(status -> {
            jdbc.update(
                    """
                    INSERT INTO identity.service_credentials (
                        id, application_user_id, client_id, tenant_id, merchant_id,
                        status, label, provisioning_operation_id, disclosure_status,
                        disclosure_consumed_at, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, 'PROVISIONING', ?, ?, 'PENDING', NULL, ?, ?)
                    """,
                    credentialId, userId, "ledgerops-sandbox-credential-" + credentialId,
                    UUID.randomUUID(), UUID.randomUUID(), "primary", operationId, now, now
            );
            jdbc.update(
                    """
                    INSERT INTO identity.service_credential_provisioning_operations (
                        id, credential_id, tenant_id, keycloak_client_id, status,
                        attempt_count, created_at, updated_at
                    )
                    SELECT ?, id, tenant_id, client_id, 'PENDING', 1, created_at, updated_at
                      FROM identity.service_credentials
                     WHERE id = ?
                    """,
                    operationId, credentialId
            );
        });

        assertThat(jdbc.queryForObject(
                "SELECT status FROM identity.service_credentials WHERE id = ?",
                String.class,
                credentialId
        )).isEqualTo("PROVISIONING");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM identity.service_credential_provisioning_operations WHERE id = ?",
                String.class,
                operationId
        )).isEqualTo("PENDING");

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE identity.service_credentials SET status = 'UNKNOWN' WHERE id = ?",
                credentialId
        )).isInstanceOf(Exception.class);
    }

    private boolean tableExists(String tableName) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                      FROM information_schema.tables
                     WHERE table_schema = 'identity' AND table_name = ?
                )
                """,
                Boolean.class,
                tableName
        ));
    }

    private void insertApplicationUser(UUID userId, Timestamp now) {
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
