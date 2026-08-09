package com.ledgerops.identity.infrastructure;

import com.ledgerops.identity.domain.ApplicationUserId;
import com.ledgerops.identity.domain.MerchantScope;
import com.ledgerops.identity.domain.TenantMembership;
import com.ledgerops.identity.domain.TenantMembershipId;
import com.ledgerops.identity.domain.TenantMembershipRepository;
import com.ledgerops.identity.domain.TenantRole;
import com.ledgerops.identity.domain.TenantRoleAssignment;
import com.ledgerops.identity.domain.TenantRoleAssignmentId;
import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class TenantMembershipPersistenceIntegrationTests {

    @Autowired
    private TenantMembershipRepository memberships;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void persistsAnActiveMembershipWithTenantScopedMerchantAssignment() {
        UUID tenantId = insertTenant();
        UUID merchantId = insertMerchant(tenantId);
        UUID userId = insertApplicationUser();
        TenantMembershipId membershipId = TenantMembershipId.newId();
        TenantRoleAssignment assignment = TenantRoleAssignment.merchantScoped(
                TenantRoleAssignmentId.newId(),
                tenantId,
                TenantRole.MERCHANT_ADMIN,
                MerchantScope.validated(tenantId, Set.of(merchantId), Map.of(merchantId, tenantId))
        );
        TenantMembership membership = TenantMembership.active(
                membershipId,
                tenantId,
                new ApplicationUserId(userId),
                Set.of(assignment)
        );

        memberships.save(membership);

        TenantMembership loaded = memberships.findById(membershipId).orElseThrow();
        TenantMembership loadedByUser = memberships
                .findActiveByApplicationUserAndTenant(new ApplicationUserId(userId), tenantId)
                .orElseThrow();

        assertThat(loaded.id()).isEqualTo(membershipId);
        assertThat(loaded.applicationUserId()).isEqualTo(new ApplicationUserId(userId));
        assertThat(loaded.tenantId()).isEqualTo(tenantId);
        assertThat(loaded.roleAssignments()).singleElement().satisfies(value -> {
            assertThat(value.role()).isEqualTo(TenantRole.MERCHANT_ADMIN);
            assertThat(value.merchantScope().merchantIds()).containsExactly(merchantId);
        });
        assertThat(loadedByUser.id()).isEqualTo(membershipId);
    }

    private UUID insertTenant() {
        UUID tenantId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update(
                """
                INSERT INTO tenancy.tenants (
                    id, name, default_currency, default_locale, status,
                    version, created_at, updated_at
                ) VALUES (?, ?, 'SAR', 'en-SA', 'ACTIVE', 0, ?, ?)
                """,
                tenantId,
                "Membership Persistence Tenant " + tenantId,
                Timestamp.from(now),
                Timestamp.from(now)
        );
        return tenantId;
    }

    private UUID insertMerchant(UUID tenantId) {
        UUID merchantId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update(
                """
                INSERT INTO merchant.merchants (
                    id, tenant_id, name, status, version, created_at, updated_at
                ) VALUES (?, ?, ?, 'ACTIVE', 0, ?, ?)
                """,
                merchantId,
                tenantId,
                "Membership Persistence Merchant " + merchantId,
                Timestamp.from(now),
                Timestamp.from(now)
        );
        return merchantId;
    }

    private UUID insertApplicationUser() {
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update(
                """
                INSERT INTO identity.application_users (
                    id, issuer, subject, status, version, created_at, updated_at
                ) VALUES (?, ?, ?, 'ACTIVE', 0, ?, ?)
                """,
                userId,
                "membership-issuer-" + userId,
                "membership-subject-" + userId,
                Timestamp.from(now),
                Timestamp.from(now)
        );
        return userId;
    }
}
