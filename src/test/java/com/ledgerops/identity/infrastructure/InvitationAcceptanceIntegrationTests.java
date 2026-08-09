package com.ledgerops.identity.infrastructure;

import com.ledgerops.identity.application.InvitationAcceptanceService;
import com.ledgerops.identity.application.InvitationNotFoundException;
import com.ledgerops.identity.domain.ApplicationUserId;
import com.ledgerops.identity.domain.InvalidInvitationException;
import com.ledgerops.identity.domain.Invitation;
import com.ledgerops.identity.domain.InvitationAcceptance;
import com.ledgerops.identity.domain.InvitationId;
import com.ledgerops.identity.domain.InvitationRepository;
import com.ledgerops.identity.domain.InvitationTokenHash;
import com.ledgerops.identity.domain.MerchantScope;
import com.ledgerops.identity.domain.TenantMembership;
import com.ledgerops.identity.domain.TenantMembershipId;
import com.ledgerops.identity.domain.TenantRole;
import com.ledgerops.identity.domain.TenantRoleAssignment;
import com.ledgerops.identity.domain.TenantRoleAssignmentId;
import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class InvitationAcceptanceIntegrationTests {

    @Autowired
    private InvitationAcceptanceService acceptanceService;

    @Autowired
    private InvitationRepository invitations;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Test
    void acceptsInvitationAndCopiesItsImmutableRoleProposalAtomically() {
        UUID tenantId = insertTenant();
        UUID merchantId = insertMerchant(tenantId);
        UUID userId = insertApplicationUser();
        TenantMembershipId membershipId = TenantMembershipId.newId();
        InvitationId invitationId = InvitationId.newId();
        InvitationTokenHash tokenHash = new InvitationTokenHash("c3".repeat(32));
        TenantRoleAssignment assignment = merchantAssignment(tenantId, merchantId);
        Invitation invitation = Invitation.create(
                invitationId,
                tenantId,
                "person@example.com",
                tokenHash,
                Set.of(assignment),
                Instant.now().minusSeconds(1)
        );

        transactions.executeWithoutResult(status -> {
            insertInvitedMembership(tenantId, membershipId);
            invitations.save(invitation, membershipId);
        });

        TenantMembership accepted = acceptanceService.accept(
                tokenHash,
                new InvitationAcceptance(new ApplicationUserId(userId), "PERSON@example.com"),
                UUID.nameUUIDFromBytes(("acceptance-" + invitationId).getBytes())
        );

        assertThat(accepted.status().name()).isEqualTo("ACTIVE");
        assertThat(accepted.applicationUserId()).isEqualTo(new ApplicationUserId(userId));
        assertThat(accepted.roleAssignments()).singleElement().satisfies(value -> {
            assertThat(value.id()).isEqualTo(assignment.id());
            assertThat(value.role()).isEqualTo(TenantRole.MERCHANT_ADMIN);
            assertThat(value.merchantScope().merchantIds()).containsExactly(merchantId);
        });

        Map<String, Object> membershipRow = jdbc.queryForMap(
                "SELECT status, application_user_id FROM identity.tenant_memberships WHERE id = ?",
                membershipId.value()
        );
        assertThat(membershipRow.get("status")).isEqualTo("ACTIVE");
        assertThat(membershipRow.get("application_user_id")).isEqualTo(userId);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM identity.invitations WHERE id = ?",
                String.class,
                invitationId.value()
        )).isEqualTo("CONSUMED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM identity.tenant_role_assignments "
                        + "WHERE membership_id = ? AND id = ?",
                Integer.class,
                membershipId.value(),
                assignment.id().value()
        )).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM identity.role_assignment_merchant_scopes "
                        + "WHERE role_assignment_id = ? AND merchant_id = ?",
                Integer.class,
                assignment.id().value(),
                merchantId
        )).isEqualTo(1);
        assertThat(invitations.findPendingByTokenHash(tokenHash)).isEmpty();
        assertThat(invitations.findById(invitationId)).hasValueSatisfying(value ->
                assertThat(value.status().name()).isEqualTo("CONSUMED"));
        assertThat(jdbc.queryForMap(
                "SELECT action_type, target_type, target_id, correlation_id, details "
                        + "FROM audit.audit_records WHERE target_id = ?",
                membershipId.value().toString()
        )).satisfies(row -> {
            assertThat(row.get("action_type")).isEqualTo("identity.membership.accepted");
            assertThat(row.get("target_type")).isEqualTo("tenant-membership");
            assertThat(row.get("correlation_id"))
                    .isEqualTo(UUID.nameUUIDFromBytes(
                            ("acceptance-" + invitationId).getBytes()).toString());
            assertThat(row.get("details").toString())
                    .contains(userId.toString())
                    .doesNotContain("token");
        });
        assertThat(jdbc.queryForMap(
                "SELECT producer_name, message_type, deduplication_key, topic, "
                        + "partition_key, aggregate_id, correlation_id, causation_id, payload "
                        + "FROM messaging.outbox "
                        + "WHERE producer_name = 'identity' AND aggregate_id = ?",
                membershipId.value()
        )).satisfies(row -> {
            assertThat(row.get("producer_name")).isEqualTo("identity");
            assertThat(row.get("message_type")).isEqualTo("IdentityLifecycleChanged");
            assertThat(row.get("deduplication_key")).isEqualTo(
                    "identity-event:TenantMembership:" + membershipId.value() + ":1");
            assertThat(row.get("topic")).isEqualTo("ledgerops.identity.lifecycle.v1");
            assertThat(row.get("partition_key")).isEqualTo(tenantId.toString());
            assertThat(row.get("correlation_id")).isEqualTo(
                    UUID.nameUUIDFromBytes(("acceptance-" + invitationId).getBytes()));
            assertThat(row.get("causation_id")).isEqualTo(invitationId.value());
            assertThat(row.get("payload").toString())
                    .contains("\"event\":\"ACCEPTED\"")
                    .contains("\"version\":1")
                    .contains(userId.toString());
        });

        assertThatThrownBy(() -> acceptanceService.accept(
                tokenHash,
                new InvitationAcceptance(new ApplicationUserId(userId), "person@example.com"),
                UUID.nameUUIDFromBytes(("replay-" + invitationId).getBytes())
        )).isExactlyInstanceOf(InvitationNotFoundException.class);
    }

    @Test
    void rejectsWrongVerifiedEmailWithoutChangingInvitationOrMembership() {
        UUID tenantId = insertTenant();
        UUID merchantId = insertMerchant(tenantId);
        UUID userId = insertApplicationUser();
        TenantMembershipId membershipId = TenantMembershipId.newId();
        InvitationId invitationId = InvitationId.newId();
        InvitationTokenHash tokenHash = new InvitationTokenHash("d4".repeat(32));
        Invitation invitation = Invitation.create(
                invitationId,
                tenantId,
                "person@example.com",
                tokenHash,
                Set.of(merchantAssignment(tenantId, merchantId)),
                Instant.now().minusSeconds(1)
        );

        transactions.executeWithoutResult(status -> {
            insertInvitedMembership(tenantId, membershipId);
            invitations.save(invitation, membershipId);
        });

        assertThatThrownBy(() -> acceptanceService.accept(
                tokenHash,
                new InvitationAcceptance(new ApplicationUserId(userId), "wrong@example.com"),
                UUID.nameUUIDFromBytes(("wrong-email-" + invitationId).getBytes())
        )).isExactlyInstanceOf(InvalidInvitationException.class);

        assertThat(jdbc.queryForObject(
                "SELECT status FROM identity.tenant_memberships WHERE id = ?",
                String.class,
                membershipId.value()
        )).isEqualTo("INVITED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM identity.invitations WHERE id = ?",
                String.class,
                invitationId.value()
        )).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit.audit_records WHERE target_id = ?",
                Integer.class,
                membershipId.value().toString()
        )).isZero();
    }

    private TenantRoleAssignment merchantAssignment(UUID tenantId, UUID merchantId) {
        return TenantRoleAssignment.merchantScoped(
                TenantRoleAssignmentId.newId(),
                tenantId,
                TenantRole.MERCHANT_ADMIN,
                MerchantScope.validated(tenantId, Set.of(merchantId), Map.of(merchantId, tenantId))
        );
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
                "Invitation Acceptance Tenant " + tenantId,
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
                "Invitation Acceptance Merchant " + merchantId,
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
                "invitation-acceptance-issuer-" + userId,
                "invitation-acceptance-subject-" + userId,
                Timestamp.from(now),
                Timestamp.from(now)
        );
        return userId;
    }

    private void insertInvitedMembership(UUID tenantId, TenantMembershipId membershipId) {
        Instant now = Instant.now();
        jdbc.update(
                """
                INSERT INTO identity.tenant_memberships (
                    id, application_user_id, tenant_id, status, version,
                    created_at, updated_at
                ) VALUES (?, NULL, ?, 'INVITED', 0, ?, ?)
                """,
                membershipId.value(),
                tenantId,
                Timestamp.from(now),
                Timestamp.from(now)
        );
    }
}
