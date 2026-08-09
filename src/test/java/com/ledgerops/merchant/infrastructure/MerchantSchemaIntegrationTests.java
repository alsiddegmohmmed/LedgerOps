package com.ledgerops.merchant.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class MerchantSchemaIntegrationTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactions;

    @Test
    void createsMerchantOwnedTableThroughFlyway() {
        Boolean tableExists = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.tables
                    WHERE table_schema = 'merchant'
                      AND table_name = 'merchants'
                )
                """,
                Boolean.class
        );

        assertEquals(Boolean.TRUE, tableExists);
    }

    @Test
    void databaseRejectsMerchantWithoutTenantOwnership() {
        Instant now = Instant.parse("2026-07-18T00:00:00Z");

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        """
                        INSERT INTO merchant.merchants (
                            id,
                            tenant_id,
                            name,
                            status,
                            created_at,
                            updated_at
                        ) VALUES (?, CAST(? AS UUID), ?, ?, ?, ?)
                        """,
                        UUID.randomUUID(),
                        null,
                        "Ownerless Merchant",
                        "ACTIVE",
                        Timestamp.from(now),
                        Timestamp.from(now)
                )
        );
    }

    @Test
    void addsTenantOwnershipAndLifecycleLockSupportForSliceTwoB() {
        Integer compoundOwnershipConstraints = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                  FROM pg_constraint
                 WHERE conrelid = 'merchant.merchants'::regclass
                   AND conname = 'uk_merchants_tenant_id_id'
                """,
                Integer.class
        );
        Integer lifecycleIndexes = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                  FROM pg_indexes
                 WHERE schemaname = 'merchant'
                   AND tablename = 'merchants'
                   AND indexname = 'ix_merchants_tenant_lifecycle_lock'
                """,
                Integer.class
        );

        assertThat(compoundOwnershipConstraints).isEqualTo(1);
        assertThat(lifecycleIndexes).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                  FROM pg_constraint
                 WHERE conrelid = 'merchant.merchants'::regclass
                   AND conname = 'fk_merchants_tenant'
                """,
                Integer.class
        )).isEqualTo(1);
    }

    @Test
    void rejectsNonexistentTenantOwnership() {
        UUID missingTenantId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-01T12:00:00Z");

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO merchant.merchants (
                    id, tenant_id, name, status, version, created_at, updated_at
                ) VALUES (?, ?, ?, 'ACTIVE', 0, ?, ?)
                """,
                UUID.randomUUID(), missingTenantId,
                "Missing Owner Merchant " + missingTenantId,
                Timestamp.from(now), Timestamp.from(now)
        )).isInstanceOf(Exception.class);
    }

    @Test
    void rejectsChangingPermanentTenantOwnershipForUnscopedMerchant() {
        UUID merchantId = UUID.randomUUID();
        UUID tenantId = insertTenant();
        UUID otherTenantId = insertTenant();
        Instant now = Instant.parse("2026-08-01T12:00:00Z");
        jdbcTemplate.update(
                """
                INSERT INTO merchant.merchants (
                    id, tenant_id, name, status, version, created_at, updated_at
                ) VALUES (?, ?, ?, 'ACTIVE', 0, ?, ?)
                """,
                merchantId,
                tenantId,
                "Permanent Owner Merchant " + merchantId,
                Timestamp.from(now),
                Timestamp.from(now)
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE merchant.merchants SET tenant_id = ? WHERE id = ?",
                otherTenantId,
                merchantId
        )).isInstanceOf(Exception.class);
    }

    @Test
    void rejectsCoordinatedMerchantAndScopedMembershipTenantTransfer() {
        UUID originalTenantId = insertTenant();
        UUID targetTenantId = insertTenant();
        UUID merchantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.parse("2026-08-01T12:00:00Z"));

        jdbcTemplate.update(
                """
                INSERT INTO merchant.merchants
                    (id, tenant_id, name, status, version, created_at, updated_at)
                VALUES (?, ?, ?, 'ACTIVE', 0, ?, ?)
                """,
                merchantId, originalTenantId, "Scoped Merchant " + merchantId, now, now
        );
        jdbcTemplate.update(
                """
                INSERT INTO identity.application_users
                    (id, issuer, subject, status, version, created_at, updated_at)
                VALUES (?, ?, ?, 'ACTIVE', 0, ?, ?)
                """,
                userId, "issuer-" + userId, "subject-" + userId, now, now
        );
        jdbcTemplate.update(
                """
                INSERT INTO identity.tenant_memberships
                    (id, application_user_id, tenant_id, status, version, created_at, updated_at)
                VALUES (?, ?, ?, 'ACTIVE', 0, ?, ?)
                """,
                membershipId, userId, originalTenantId, now, now
        );
        transactions.executeWithoutResult(status -> {
            jdbcTemplate.update(
                    """
                    INSERT INTO identity.tenant_role_assignments
                        (id, membership_id, role, scope_mode)
                    VALUES (?, ?, 'MERCHANT_ADMIN', 'MERCHANT_SET')
                    """,
                    assignmentId, membershipId
            );
            jdbcTemplate.update(
                    """
                    INSERT INTO identity.role_assignment_merchant_scopes
                        (role_assignment_id, merchant_id)
                    VALUES (?, ?)
                    """,
                    assignmentId, merchantId
            );
        });

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            jdbcTemplate.update(
                    "UPDATE merchant.merchants SET tenant_id = ? WHERE id = ?",
                    targetTenantId, merchantId
            );
            jdbcTemplate.update(
                    "UPDATE identity.tenant_memberships SET tenant_id = ? WHERE id = ?",
                    targetTenantId, membershipId
            );
        })).isInstanceOf(Exception.class);
    }

    private UUID insertTenant() {
        UUID tenantId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.parse("2026-08-01T12:00:00Z"));
        jdbcTemplate.update(
                """
                INSERT INTO tenancy.tenants
                    (id, name, default_currency, default_locale, status,
                     version, created_at, updated_at)
                VALUES (?, ?, 'SAR', 'en-SA', 'PENDING_ACTIVATION', 0, ?, ?)
                """,
                tenantId, "Merchant Owner Tenant " + tenantId, now, now
        );
        return tenantId;
    }
}
