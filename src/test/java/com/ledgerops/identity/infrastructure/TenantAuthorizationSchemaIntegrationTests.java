package com.ledgerops.identity.infrastructure;

import com.ledgerops.support.PostgresTestConfiguration;
import com.ledgerops.identity.domain.ApplicationUserId;
import com.ledgerops.identity.domain.PrincipalType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class TenantAuthorizationSchemaIntegrationTests {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PostgresAuthorizedTenantContextAdapter tenantContexts;

    @Autowired
    private TransactionTemplate transactions;

    @Test
    void rejectsEmptyMerchantSetsAndMerchantScopesForTenantWideRoles() {
        UUID userId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        insertTenant(tenantId, now);
        jdbc.update("insert into identity.application_users (id, issuer, subject, status, version, created_at, updated_at) values (?, ?, ?, 'ACTIVE', 0, ?, ?)",
                userId, "issuer-" + userId, "subject-" + userId, now, now);
        jdbc.update("insert into identity.tenant_memberships (id, application_user_id, tenant_id, status, created_at, updated_at) values (?, ?, ?, 'ACTIVE', ?, ?)",
                membershipId, userId, tenantId, now, now);

        assertThatThrownBy(() -> jdbc.update("insert into identity.tenant_role_assignments (id, membership_id, role, scope_mode) values (?, ?, 'MERCHANT_ADMIN', 'MERCHANT_SET')",
                assignmentId, membershipId)).isInstanceOf(Exception.class);

        jdbc.update("insert into identity.tenant_role_assignments (id, membership_id, role, scope_mode) values (?, ?, 'TENANT_ADMIN', 'TENANT_WIDE')",
                assignmentId, membershipId);
        assertThatThrownBy(() -> jdbc.update("insert into identity.role_assignment_merchant_scopes (role_assignment_id, merchant_id) values (?, ?)",
                assignmentId, UUID.randomUUID())).isInstanceOf(Exception.class);
    }

    @Test
    void rejectsActiveAuthorityForANonexistentTenant() {
        UUID userId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into identity.application_users (id, issuer, subject, status, version, created_at, updated_at) values (?, ?, ?, 'ACTIVE', 0, ?, ?)",
                userId, "issuer-" + userId, "subject-" + userId, now, now);

        assertThatThrownBy(() -> jdbc.update(
                "insert into identity.tenant_memberships (id, application_user_id, tenant_id, status, created_at, updated_at) values (?, ?, ?, 'ACTIVE', ?, ?)",
                UUID.randomUUID(), userId, UUID.randomUUID(), now, now
        )).isInstanceOf(Exception.class);
    }

    @Test
    void rejectsPhysicalDeletionOfStandaloneRevokedMembershipHistory() {
        UUID tenantId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        insertTenant(tenantId, now);
        jdbc.update(
                """
                INSERT INTO identity.tenant_memberships
                    (id, application_user_id, tenant_id, status, version, created_at, updated_at)
                VALUES (?, NULL, ?, 'REVOKED', 0, ?, ?)
                """,
                membershipId, tenantId, now, now
        );

        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM identity.tenant_memberships WHERE id = ?",
                membershipId
        )).isInstanceOf(Exception.class);

        assertThat(jdbc.queryForObject(
                "SELECT status FROM identity.tenant_memberships WHERE id = ?",
                String.class,
                membershipId
        )).isEqualTo("REVOKED");
    }

    @Test
    void derivesPermissionsAndMerchantScopeFromActivePostgresMembership() {
        UUID userId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        var context = transactions.execute(status -> {
            insertTenant(tenantId, now);
            jdbc.update("insert into identity.application_users (id, issuer, subject, status, version, created_at, updated_at) values (?, ?, ?, 'ACTIVE', 0, ?, ?)",
                    userId, "issuer-" + userId, "subject-" + userId, now, now);
            jdbc.update("insert into identity.tenant_memberships (id, application_user_id, tenant_id, status, created_at, updated_at) values (?, ?, ?, 'ACTIVE', ?, ?)",
                    membershipId, userId, tenantId, now, now);
            jdbc.update("insert into merchant.merchants (id, tenant_id, name, status, version, created_at, updated_at) values (?, ?, ?, 'ACTIVE', 0, ?, ?)",
                    merchantId, tenantId, "Scoped Merchant", now, now);
            jdbc.update("insert into identity.tenant_role_assignments (id, membership_id, role, scope_mode) values (?, ?, 'MERCHANT_ADMIN', 'MERCHANT_SET')",
                    assignmentId, membershipId);
            jdbc.update("insert into identity.role_assignment_merchant_scopes (role_assignment_id, merchant_id) values (?, ?)",
                    assignmentId, merchantId);
            return tenantContexts.find(new ApplicationUserId(userId), PrincipalType.HUMAN, null, tenantId);
        });

        assertThat(context).hasValueSatisfying(value -> {
            assertThat(value.scopeMode().name()).isEqualTo("MERCHANT_SET");
            assertThat(value.merchantIds()).containsExactly(merchantId);
            assertThat(value.permissions().stream().map(Enum::name))
                    .contains("MERCHANT_READ", "CREDENTIAL_MANAGE");
        });
    }

    @Test
    void rejectsMerchantScopeFromAnotherTenant() {
        UUID userId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        UUID membershipTenantId = UUID.randomUUID();
        UUID merchantTenantId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        insertTenant(membershipTenantId, now);
        insertTenant(merchantTenantId, now);
        jdbc.update("insert into identity.application_users (id, issuer, subject, status, version, created_at, updated_at) values (?, ?, ?, 'ACTIVE', 0, ?, ?)",
                userId, "issuer-" + userId, "subject-" + userId, now, now);
        jdbc.update("insert into identity.tenant_memberships (id, application_user_id, tenant_id, status, created_at, updated_at) values (?, ?, ?, 'ACTIVE', ?, ?)",
                membershipId, userId, membershipTenantId, now, now);
        jdbc.update("insert into merchant.merchants (id, tenant_id, name, status, version, created_at, updated_at) values (?, ?, ?, 'ACTIVE', 0, ?, ?)",
                merchantId, merchantTenantId, "Other Tenant Merchant", now, now);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            jdbc.update("insert into identity.tenant_role_assignments (id, membership_id, role, scope_mode) values (?, ?, 'MERCHANT_ADMIN', 'MERCHANT_SET')",
                    assignmentId, membershipId);
            jdbc.update("insert into identity.role_assignment_merchant_scopes (role_assignment_id, merchant_id) values (?, ?)",
                    assignmentId, merchantId);
        })).isInstanceOf(JpaSystemException.class);
    }

    @Test
    void acceptsMerchantScopeFromTheMembershipTenant() {
        UUID userId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        insertTenant(tenantId, now);
        jdbc.update("insert into identity.application_users (id, issuer, subject, status, version, created_at, updated_at) values (?, ?, ?, 'ACTIVE', 0, ?, ?)",
                userId, "issuer-" + userId, "subject-" + userId, now, now);
        jdbc.update("insert into identity.tenant_memberships (id, application_user_id, tenant_id, status, created_at, updated_at) values (?, ?, ?, 'ACTIVE', ?, ?)",
                membershipId, userId, tenantId, now, now);
        jdbc.update("insert into merchant.merchants (id, tenant_id, name, status, version, created_at, updated_at) values (?, ?, ?, 'ACTIVE', 0, ?, ?)",
                merchantId, tenantId, "Same Tenant Merchant", now, now);

        transactions.executeWithoutResult(status -> {
            jdbc.update("insert into identity.tenant_role_assignments (id, membership_id, role, scope_mode) values (?, ?, 'MERCHANT_ADMIN', 'MERCHANT_SET')",
                    assignmentId, membershipId);
            jdbc.update("insert into identity.role_assignment_merchant_scopes (role_assignment_id, merchant_id) values (?, ?)",
                    assignmentId, merchantId);
        });
    }

    @Test
    void rejectsMovingAScopedMembershipToAnotherTenant() {
        UUID userId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID otherTenantId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        insertTenant(tenantId, now);
        insertTenant(otherTenantId, now);
        jdbc.update("insert into identity.application_users (id, issuer, subject, status, version, created_at, updated_at) values (?, ?, ?, 'ACTIVE', 0, ?, ?)",
                userId, "issuer-" + userId, "subject-" + userId, now, now);
        jdbc.update("insert into identity.tenant_memberships (id, application_user_id, tenant_id, status, created_at, updated_at) values (?, ?, ?, 'ACTIVE', ?, ?)",
                membershipId, userId, tenantId, now, now);
        jdbc.update("insert into merchant.merchants (id, tenant_id, name, status, version, created_at, updated_at) values (?, ?, ?, 'ACTIVE', 0, ?, ?)",
                merchantId, tenantId, "Scoped Membership Merchant", now, now);
        transactions.executeWithoutResult(status -> {
            jdbc.update("insert into identity.tenant_role_assignments (id, membership_id, role, scope_mode) values (?, ?, 'MERCHANT_ADMIN', 'MERCHANT_SET')",
                    assignmentId, membershipId);
            jdbc.update("insert into identity.role_assignment_merchant_scopes (role_assignment_id, merchant_id) values (?, ?)",
                    assignmentId, merchantId);
        });

        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                jdbc.update("update identity.tenant_memberships set tenant_id = ? where id = ?", otherTenantId, membershipId)))
                .isInstanceOf(Exception.class);
    }

    @Test
    void rejectsMovingAScopedMerchantToAnotherTenant() {
        UUID userId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID otherTenantId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        insertTenant(tenantId, now);
        insertTenant(otherTenantId, now);
        jdbc.update("insert into identity.application_users (id, issuer, subject, status, version, created_at, updated_at) values (?, ?, ?, 'ACTIVE', 0, ?, ?)",
                userId, "issuer-" + userId, "subject-" + userId, now, now);
        jdbc.update("insert into identity.tenant_memberships (id, application_user_id, tenant_id, status, created_at, updated_at) values (?, ?, ?, 'ACTIVE', ?, ?)",
                membershipId, userId, tenantId, now, now);
        jdbc.update("insert into merchant.merchants (id, tenant_id, name, status, version, created_at, updated_at) values (?, ?, ?, 'ACTIVE', 0, ?, ?)",
                merchantId, tenantId, "Scoped Merchant", now, now);
        transactions.executeWithoutResult(status -> {
            jdbc.update("insert into identity.tenant_role_assignments (id, membership_id, role, scope_mode) values (?, ?, 'MERCHANT_ADMIN', 'MERCHANT_SET')",
                    assignmentId, membershipId);
            jdbc.update("insert into identity.role_assignment_merchant_scopes (role_assignment_id, merchant_id) values (?, ?)",
                    assignmentId, merchantId);
        });

        assertThatThrownBy(() -> jdbc.update("update merchant.merchants set tenant_id = ? where id = ?", otherTenantId, merchantId))
                .isInstanceOf(Exception.class);
    }

    @Test
    void rejectsDeletingAScopedMerchant() {
        UUID userId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        insertTenant(tenantId, now);
        jdbc.update("insert into identity.application_users (id, issuer, subject, status, version, created_at, updated_at) values (?, ?, ?, 'ACTIVE', 0, ?, ?)",
                userId, "issuer-" + userId, "subject-" + userId, now, now);
        jdbc.update("insert into identity.tenant_memberships (id, application_user_id, tenant_id, status, created_at, updated_at) values (?, ?, ?, 'ACTIVE', ?, ?)",
                membershipId, userId, tenantId, now, now);
        jdbc.update("insert into merchant.merchants (id, tenant_id, name, status, version, created_at, updated_at) values (?, ?, ?, 'ACTIVE', 0, ?, ?)",
                merchantId, tenantId, "Scoped Merchant", now, now);
        transactions.executeWithoutResult(status -> {
            jdbc.update("insert into identity.tenant_role_assignments (id, membership_id, role, scope_mode) values (?, ?, 'MERCHANT_ADMIN', 'MERCHANT_SET')",
                    assignmentId, membershipId);
            jdbc.update("insert into identity.role_assignment_merchant_scopes (role_assignment_id, merchant_id) values (?, ?)",
                    assignmentId, merchantId);
        });

        assertThatThrownBy(() -> jdbc.update("delete from merchant.merchants where id = ?", merchantId))
                .isInstanceOf(Exception.class);
    }

    @Test
    void rejectsDeferredMultiRowTenantMoveBecauseOwnershipIsPermanent() {
        UUID userId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into tenancy.tenants (id, name, default_currency, default_locale, status, version, created_at, updated_at) values (?, ?, 'USD', 'en', 'ACTIVE', 0, ?, ?)",
                tenantA, "Tenant A " + tenantA, now, now);
        jdbc.update("insert into tenancy.tenants (id, name, default_currency, default_locale, status, version, created_at, updated_at) values (?, ?, 'USD', 'en', 'ACTIVE', 0, ?, ?)",
                tenantB, "Tenant B " + tenantB, now, now);
        jdbc.update("insert into identity.application_users (id, issuer, subject, status, version, created_at, updated_at) values (?, ?, ?, 'ACTIVE', 0, ?, ?)",
                userId, "issuer-" + userId, "subject-" + userId, now, now);
        jdbc.update("insert into identity.tenant_memberships (id, application_user_id, tenant_id, status, created_at, updated_at) values (?, ?, ?, 'ACTIVE', ?, ?)",
                membershipId, userId, tenantA, now, now);
        jdbc.update("insert into merchant.merchants (id, tenant_id, name, status, version, created_at, updated_at) values (?, ?, ?, 'ACTIVE', 0, ?, ?)",
                merchantId, tenantA, "Deferred Move Merchant " + merchantId, now, now);
        transactions.executeWithoutResult(status -> {
            jdbc.update("insert into identity.tenant_role_assignments (id, membership_id, role, scope_mode) values (?, ?, 'MERCHANT_ADMIN', 'MERCHANT_SET')",
                    assignmentId, membershipId);
            jdbc.update("insert into identity.role_assignment_merchant_scopes (role_assignment_id, merchant_id) values (?, ?)",
                    assignmentId, merchantId);
        });

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            jdbc.update("update identity.tenant_memberships set tenant_id = ? where id = ?", tenantB, membershipId);
            jdbc.update("update merchant.merchants set tenant_id = ? where id = ?", tenantB, merchantId);
        })).isInstanceOf(Exception.class);

        assertThat(jdbc.queryForObject("select tenant_id from identity.tenant_memberships where id = ?", UUID.class, membershipId))
                .isEqualTo(tenantA);
        assertThat(jdbc.queryForObject("select tenant_id from merchant.merchants where id = ?", UUID.class, merchantId))
                .isEqualTo(tenantA);
        assertThat(jdbc.queryForObject("select count(*) from identity.role_assignment_merchant_scopes where role_assignment_id = ? and merchant_id = ?",
                Integer.class, assignmentId, merchantId)).isEqualTo(1);
    }

    @Test
    void servicePrincipalReceivesOnlyTheActiveCredentialDerivedPaymentAuthority() {
        UUID userId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into identity.application_users (id, issuer, subject, status, version, created_at, updated_at) values (?, ?, ?, 'ACTIVE', 0, ?, ?)",
                userId, "issuer-" + userId, "subject-" + userId, now, now);
        transactions.executeWithoutResult(status -> {
            String clientId = "ledgerops-sandbox-credential-" + credentialId;
            jdbc.update(
                    """
                    INSERT INTO identity.service_credentials (
                        id, application_user_id, client_id, tenant_id, merchant_id,
                        status, label, provisioning_operation_id, disclosure_status,
                        disclosure_consumed_at, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, 'CONSUMED', ?, ?, ?)
                    """,
                    credentialId, userId, clientId, tenantId, merchantId,
                    "legacy test credential", operationId, now, now, now
            );
            jdbc.update(
                    """
                    INSERT INTO identity.service_credential_provisioning_operations (
                        id, credential_id, tenant_id, keycloak_client_id, status,
                        attempt_count, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, 'COMPLETED', 0, ?, ?)
                    """,
                    operationId, credentialId, tenantId, clientId, now, now
            );
        });

        var context = tenantContexts.find(
                null,
                PrincipalType.SERVICE,
                "ledgerops-sandbox-credential-" + credentialId,
                UUID.randomUUID()
        );

        assertThat(context).hasValueSatisfying(value -> {
            assertThat(value.tenantId()).isEqualTo(tenantId);
            assertThat(value.merchantIds()).containsExactly(merchantId);
            assertThat(value.permissions()).containsExactly(com.ledgerops.identity.domain.Permission.PAYMENT_CREATE);
            assertThat(value.serviceCredentialId()).isEqualTo(credentialId);
        });

        jdbc.update("update identity.service_credentials set status = 'REVOKED' where id = ?", credentialId);
        assertThat(tenantContexts.find(
                null,
                PrincipalType.SERVICE,
                "ledgerops-sandbox-credential-" + credentialId,
                tenantId
        )).isEmpty();
    }

    private void insertTenant(UUID tenantId, Timestamp now) {
        jdbc.update(
                "insert into tenancy.tenants (id, name, default_currency, default_locale, status, version, created_at, updated_at) values (?, ?, 'USD', 'en', 'ACTIVE', 0, ?, ?)",
                tenantId, "Authorization Tenant " + tenantId, now, now
        );
    }
}
