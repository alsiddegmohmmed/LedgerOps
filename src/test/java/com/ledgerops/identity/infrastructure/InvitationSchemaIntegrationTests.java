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
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class InvitationSchemaIntegrationTests {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Test
    void createsInvitationGrantAndScopeTablesWithoutRawTokenPersistence() {
        assertThat(tableExists("invitations")).isTrue();
        assertThat(tableExists("invitation_grants")).isTrue();
        assertThat(tableExists("invitation_grant_merchant_scopes")).isTrue();

        Integer rawTokenColumns = jdbc.queryForObject(
                """
                SELECT count(*)
                  FROM information_schema.columns
                 WHERE table_schema = 'identity'
                   AND table_name = 'invitations'
                   AND column_name IN ('token', 'raw_token', 'invitation_token')
                """,
                Integer.class
        );
        assertThat(rawTokenColumns).isZero();

        Integer foreignKeyIndexes = jdbc.queryForObject(
                """
                SELECT count(*)
                  FROM pg_indexes
                 WHERE schemaname = 'identity'
                   AND indexname IN (
                       'ix_role_assignment_merchant_scopes_merchant',
                       'ix_invitation_grant_scopes_merchant_tenant'
                   )
                """,
                Integer.class
        );
        assertThat(foreignKeyIndexes).isEqualTo(2);
    }

    @Test
    void permitsOnlyInvitedMembershipsToRemainUnlinkedAndRequiresInvitation() {
        Timestamp now = Timestamp.from(NOW);
        UUID tenantId = insertTenant();
        UUID membershipId = UUID.randomUUID();
        insertTenantWideInvitation(
                tenantId, membershipId, UUID.randomUUID(), "0".repeat(64)
        );

        assertThat(jdbc.queryForObject(
                "SELECT application_user_id IS NULL FROM identity.tenant_memberships WHERE id = ?",
                Boolean.class,
                membershipId
        )).isTrue();

        assertThatThrownBy(() -> jdbc.update(
                """
                INSERT INTO identity.tenant_memberships
                    (id, application_user_id, tenant_id, status, version, created_at, updated_at)
                VALUES (?, NULL, ?, 'ACTIVE', 0, ?, ?)
                """,
                UUID.randomUUID(), tenantId, now, now
        )).isInstanceOf(Exception.class);

        jdbc.update(
                """
                INSERT INTO identity.tenant_memberships
                    (id, application_user_id, tenant_id, status, version, created_at, updated_at)
                VALUES (?, NULL, ?, 'REVOKED', 0, ?, ?)
                """,
                UUID.randomUUID(), tenantId, now, now
        );

        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                insertInvitedMembership(UUID.randomUUID(), tenantId)
        )).isInstanceOf(Exception.class);
    }

    @Test
    void rejectsMembershipAndInvitationAuthorityForANonexistentTenant() {
        Timestamp now = Timestamp.from(NOW);

        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                  FROM pg_constraint
                 WHERE (conrelid = 'identity.tenant_memberships'::regclass
                        AND conname = 'fk_tenant_memberships_tenant')
                    OR (conrelid = 'identity.invitations'::regclass
                        AND conname = 'fk_invitations_membership_tenant')
                """,
                Integer.class
        )).isEqualTo(2);

        assertThatThrownBy(() -> jdbc.update(
                """
                INSERT INTO identity.tenant_memberships
                    (id, application_user_id, tenant_id, status, version, created_at, updated_at)
                VALUES (?, NULL, ?, 'REVOKED', 0, ?, ?)
                """,
                UUID.randomUUID(), UUID.randomUUID(), now, now
        )).isInstanceOf(Exception.class);
    }

    @Test
    void persistsOnlyAUniqueHashAndRequiresExactlySevenDayExpiryAndGrantIntent() {
        UUID tenantId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        UUID invitationId = UUID.randomUUID();
        String tokenHash = "a".repeat(64);

        insertTenantWideInvitation(tenantId, membershipId, invitationId, tokenHash);

        assertThat(jdbc.queryForObject(
                "SELECT expires_at FROM identity.invitations WHERE id = ?",
                Timestamp.class,
                invitationId
        ).toInstant()).isEqualTo(NOW.plus(7, ChronoUnit.DAYS));

        assertThatThrownBy(() -> insertTenantWideInvitation(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), tokenHash
        )).isInstanceOf(Exception.class);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            UUID emptyMembershipId = UUID.randomUUID();
            UUID emptyTenantId = insertTenant();
            insertInvitedMembership(emptyMembershipId, emptyTenantId);
            insertInvitation(UUID.randomUUID(), emptyMembershipId,
                    jdbc.queryForObject(
                            "SELECT tenant_id FROM identity.tenant_memberships WHERE id = ?",
                            UUID.class,
                            emptyMembershipId
                    ), "bd".repeat(32));
        })).isInstanceOf(Exception.class);
    }

    @Test
    void acceptsInvitationAndLinksMembershipOnlyAsOneAtomicStateChange() {
        UUID tenantId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        UUID invitationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        insertTenantWideInvitation(tenantId, membershipId, invitationId, "c".repeat(64));
        insertApplicationUser(userId);

        assertThatThrownBy(() -> jdbc.update(
                """
                UPDATE identity.tenant_memberships
                   SET application_user_id = ?, status = 'ACTIVE', version = version + 1,
                       updated_at = ?
                 WHERE id = ?
                """,
                userId, Timestamp.from(NOW.plusSeconds(1)), membershipId
        )).isInstanceOf(Exception.class);

        transactions.executeWithoutResult(status -> {
            Timestamp acceptedAt = Timestamp.from(NOW.plusSeconds(2));
            jdbc.update(
                    """
                    UPDATE identity.tenant_memberships
                       SET application_user_id = ?, status = 'ACTIVE', version = version + 1,
                           updated_at = ?
                     WHERE id = ?
                    """,
                    userId, acceptedAt, membershipId
            );
            copyInvitationGrants(invitationId, membershipId);
            jdbc.update(
                    """
                    UPDATE identity.invitations
                       SET status = 'CONSUMED', consumed_at = ?, version = version + 1,
                           updated_at = ?
                     WHERE id = ?
                    """,
                    acceptedAt, acceptedAt, invitationId
            );
        });

        assertThat(jdbc.queryForObject(
                "SELECT status FROM identity.invitations WHERE id = ?",
                String.class,
                invitationId
        )).isEqualTo("CONSUMED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM identity.tenant_memberships WHERE id = ?",
                String.class,
                membershipId
        )).isEqualTo("ACTIVE");

        assertThatThrownBy(() -> jdbc.update(
                """
                UPDATE identity.invitations
                   SET status = 'PENDING', consumed_at = NULL, version = version + 1,
                       updated_at = ?
                 WHERE id = ?
                """,
                Timestamp.from(NOW.plusSeconds(3)), invitationId
        )).isInstanceOf(Exception.class);

        assertThatThrownBy(() -> jdbc.update(
                """
                UPDATE identity.invitations
                   SET consumed_at = ?, version = version + 1, updated_at = ?
                 WHERE id = ?
                """,
                Timestamp.from(NOW.plusSeconds(4)),
                Timestamp.from(NOW.plusSeconds(4)),
                invitationId
        )).isInstanceOf(Exception.class);
    }

    @Test
    void rejectsConsumptionUnlessEveryGrantAndMerchantScopeIsCopiedExactly() {
        UUID tenantId = insertTenant();
        UUID membershipId = UUID.randomUUID();
        UUID invitationId = UUID.randomUUID();
        UUID tenantAdminAssignmentId = UUID.randomUUID();
        UUID merchantAdminAssignmentId = UUID.randomUUID();
        UUID intendedMerchantId = insertMerchant(tenantId);
        UUID substitutedMerchantId = insertMerchant(tenantId);
        UUID userId = UUID.randomUUID();
        insertApplicationUser(userId);

        transactions.executeWithoutResult(status -> {
            insertInvitedMembership(membershipId, tenantId);
            insertInvitation(invitationId, membershipId, tenantId, "ac".repeat(32));
            jdbc.update(
                    """
                    INSERT INTO identity.invitation_grants
                        (invitation_id, assignment_id, tenant_id, role, scope_mode)
                    VALUES (?, ?, ?, 'TENANT_ADMIN', 'TENANT_WIDE'),
                           (?, ?, ?, 'MERCHANT_ADMIN', 'MERCHANT_SET')
                    """,
                    invitationId, tenantAdminAssignmentId, tenantId,
                    invitationId, merchantAdminAssignmentId, tenantId
            );
            jdbc.update(
                    """
                    INSERT INTO identity.invitation_grant_merchant_scopes
                        (invitation_id, assignment_id, tenant_id, merchant_id)
                    VALUES (?, ?, ?, ?)
                    """,
                    invitationId, merchantAdminAssignmentId, tenantId, intendedMerchantId
            );
        });

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            activateMembership(membershipId, userId, NOW.plusSeconds(1));
            jdbc.update(
                    """
                    INSERT INTO identity.tenant_role_assignments
                        (id, membership_id, role, scope_mode)
                    VALUES (?, ?, 'TENANT_ADMIN', 'TENANT_WIDE')
                    """,
                    tenantAdminAssignmentId, membershipId
            );
            consumeInvitationRecord(invitationId, NOW.plusSeconds(1));
        })).isInstanceOf(Exception.class);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            activateMembership(membershipId, userId, NOW.plusSeconds(2));
            jdbc.update(
                    """
                    INSERT INTO identity.tenant_role_assignments
                        (id, membership_id, role, scope_mode)
                    VALUES (?, ?, 'TENANT_ADMIN', 'TENANT_WIDE'),
                           (?, ?, 'MERCHANT_ADMIN', 'MERCHANT_SET')
                    """,
                    tenantAdminAssignmentId, membershipId,
                    merchantAdminAssignmentId, membershipId
            );
            jdbc.update(
                    """
                    INSERT INTO identity.role_assignment_merchant_scopes
                        (role_assignment_id, merchant_id)
                    VALUES (?, ?)
                    """,
                    merchantAdminAssignmentId, substitutedMerchantId
            );
            consumeInvitationRecord(invitationId, NOW.plusSeconds(2));
        })).isInstanceOf(Exception.class);

        assertThat(jdbc.queryForObject(
                "SELECT status FROM identity.invitations WHERE id = ?",
                String.class,
                invitationId
        )).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM identity.tenant_memberships WHERE id = ?",
                String.class,
                membershipId
        )).isEqualTo("INVITED");
    }

    @Test
    void rejectsAlreadyExpiredInvitationUsingDatabaseTransactionTime() {
        Instant databaseNow = jdbc.queryForObject(
                "SELECT transaction_timestamp()",
                Timestamp.class
        ).toInstant();
        Instant createdAt = databaseNow.minus(8, ChronoUnit.DAYS);
        Instant expiresAt = createdAt.plus(7, ChronoUnit.DAYS);
        UUID tenantId = insertTenant();
        UUID membershipId = UUID.randomUUID();
        UUID invitationId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        insertApplicationUser(userId);

        transactions.executeWithoutResult(status -> {
            Timestamp created = Timestamp.from(createdAt);
            insertInvitedMembership(membershipId, tenantId);
            jdbc.update(
                    """
                    INSERT INTO identity.invitations (
                        id, tenant_id, membership_id, intended_email, token_hash, status,
                        version, created_at, expires_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, 'PENDING', 0, ?, ?, ?)
                    """,
                    invitationId, tenantId, membershipId,
                    "expired-" + invitationId + "@example.test", "bc".repeat(32),
                    created, Timestamp.from(expiresAt), created
            );
            jdbc.update(
                    """
                    INSERT INTO identity.invitation_grants
                        (invitation_id, assignment_id, tenant_id, role, scope_mode)
                    VALUES (?, ?, ?, 'TENANT_ADMIN', 'TENANT_WIDE')
                    """,
                    invitationId, assignmentId, tenantId
            );
        });

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            activateMembership(membershipId, userId, databaseNow);
            copyInvitationGrants(invitationId, membershipId);
            Timestamp backdatedConsumption = Timestamp.from(expiresAt.minusSeconds(1));
            jdbc.update(
                    """
                    UPDATE identity.invitations
                       SET status = 'CONSUMED', consumed_at = ?, version = version + 1,
                           updated_at = ?
                     WHERE id = ?
                    """,
                    backdatedConsumption, Timestamp.from(databaseNow), invitationId
            );
        })).isInstanceOf(Exception.class);
    }

    @Test
    void rejectsExpiredConsumptionAndInvalidTerminalEventChronology() {
        UUID expiredTenantId = UUID.randomUUID();
        UUID expiredMembershipId = UUID.randomUUID();
        UUID expiredInvitationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        insertTenantWideInvitation(
                expiredTenantId, expiredMembershipId, expiredInvitationId, "9".repeat(64)
        );
        insertApplicationUser(userId);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            Timestamp expiresAt = Timestamp.from(NOW.plus(7, ChronoUnit.DAYS));
            jdbc.update(
                    """
                    UPDATE identity.tenant_memberships
                       SET application_user_id = ?, status = 'ACTIVE', version = version + 1,
                           updated_at = ?
                     WHERE id = ?
                    """,
                    userId, expiresAt, expiredMembershipId
            );
            jdbc.update(
                    """
                    UPDATE identity.invitations
                       SET status = 'CONSUMED', consumed_at = ?, version = version + 1,
                           updated_at = ?
                     WHERE id = ?
                    """,
                    expiresAt, expiresAt, expiredInvitationId
            );
        })).isInstanceOf(Exception.class);

        UUID earlyTenantId = UUID.randomUUID();
        UUID earlyMembershipId = UUID.randomUUID();
        UUID earlyInvitationId = UUID.randomUUID();
        UUID earlyUserId = UUID.randomUUID();
        insertTenantWideInvitation(
                earlyTenantId, earlyMembershipId, earlyInvitationId, "ab".repeat(32)
        );
        insertApplicationUser(earlyUserId);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            Timestamp updatedAt = Timestamp.from(NOW.plusSeconds(1));
            Timestamp consumedAt = Timestamp.from(NOW.minusSeconds(1));
            jdbc.update(
                    """
                    UPDATE identity.tenant_memberships
                       SET application_user_id = ?, status = 'ACTIVE', version = version + 1,
                           updated_at = ?
                     WHERE id = ?
                    """,
                    earlyUserId, updatedAt, earlyMembershipId
            );
            jdbc.update(
                    """
                    UPDATE identity.invitations
                       SET status = 'CONSUMED', consumed_at = ?, version = version + 1,
                           updated_at = ?
                     WHERE id = ?
                    """,
                    consumedAt, updatedAt, earlyInvitationId
            );
        })).isInstanceOf(Exception.class);

        UUID revokedTenantId = UUID.randomUUID();
        UUID revokedMembershipId = UUID.randomUUID();
        UUID revokedInvitationId = UUID.randomUUID();
        insertTenantWideInvitation(
                revokedTenantId, revokedMembershipId, revokedInvitationId, "b".repeat(64)
        );

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            Timestamp updatedAt = Timestamp.from(NOW.plusSeconds(1));
            Timestamp revokedAt = Timestamp.from(NOW.plusSeconds(2));
            jdbc.update(
                    """
                    UPDATE identity.tenant_memberships
                       SET status = 'REVOKED', version = version + 1, updated_at = ?
                     WHERE id = ?
                    """,
                    updatedAt, revokedMembershipId
            );
            jdbc.update(
                    """
                    UPDATE identity.invitations
                       SET status = 'REVOKED', revoked_at = ?, version = version + 1,
                           updated_at = ?
                     WHERE id = ?
                    """,
                    revokedAt, updatedAt, revokedInvitationId
            );
        })).isInstanceOf(Exception.class);
    }

    @Test
    void consumedInvitationPreservesAcceptanceEvidenceThroughLaterMembershipLifecycle() {
        UUID tenantId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        UUID invitationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        insertTenantWideInvitation(tenantId, membershipId, invitationId, "6".repeat(64));
        insertApplicationUser(userId);
        transactions.executeWithoutResult(status -> consumeInvitation(
                membershipId, invitationId, userId
        ));

        updateMembershipStatus(membershipId, "SUSPENDED", NOW.plusSeconds(2));
        updateMembershipStatus(membershipId, "ACTIVE", NOW.plusSeconds(3));
        updateMembershipStatus(membershipId, "REVOKED", NOW.plusSeconds(4));

        assertThat(jdbc.queryForObject(
                "SELECT status FROM identity.tenant_memberships WHERE id = ?",
                String.class,
                membershipId
        )).isEqualTo("REVOKED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM identity.invitations WHERE id = ?",
                String.class,
                invitationId
        )).isEqualTo("CONSUMED");
    }

    @Test
    void consumedInvitationAssignmentIdentityCannotBeMutatedOrReused() {
        UUID tenantId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        UUID invitationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        insertTenantWideInvitation(tenantId, membershipId, invitationId, "d1".repeat(32));
        insertApplicationUser(userId);
        transactions.executeWithoutResult(status -> consumeInvitation(
                membershipId, invitationId, userId
        ));
        UUID acceptedAssignmentId = jdbc.queryForObject(
                "SELECT assignment_id FROM identity.invitation_grants WHERE invitation_id = ?",
                UUID.class,
                invitationId
        );

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE identity.tenant_role_assignments SET role = 'VIEWER' WHERE id = ?",
                acceptedAssignmentId
        )).isInstanceOf(Exception.class);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            jdbc.update(
                    "DELETE FROM identity.tenant_role_assignments WHERE id = ?",
                    acceptedAssignmentId
            );
            jdbc.update(
                    """
                    INSERT INTO identity.tenant_role_assignments
                        (id, membership_id, role, scope_mode)
                    VALUES (?, ?, 'VIEWER', 'TENANT_WIDE')
                    """,
                    acceptedAssignmentId, membershipId
            );
        })).isInstanceOf(Exception.class);
    }

    @Test
    void laterRoleManagementUsesANewAssignmentIdentity() {
        UUID tenantId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        UUID invitationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID replacementAssignmentId = UUID.randomUUID();
        insertTenantWideInvitation(tenantId, membershipId, invitationId, "d2".repeat(32));
        insertApplicationUser(userId);
        transactions.executeWithoutResult(status -> consumeInvitation(
                membershipId, invitationId, userId
        ));
        UUID acceptedAssignmentId = jdbc.queryForObject(
                "SELECT assignment_id FROM identity.invitation_grants WHERE invitation_id = ?",
                UUID.class,
                invitationId
        );

        transactions.executeWithoutResult(status -> {
            jdbc.update(
                    "DELETE FROM identity.tenant_role_assignments WHERE id = ?",
                    acceptedAssignmentId
            );
            jdbc.update(
                    """
                    INSERT INTO identity.tenant_role_assignments
                        (id, membership_id, role, scope_mode)
                    VALUES (?, ?, 'VIEWER', 'TENANT_WIDE')
                    """,
                    replacementAssignmentId, membershipId
            );
        });

        assertThat(jdbc.queryForObject(
                "SELECT role FROM identity.tenant_role_assignments WHERE id = ?",
                String.class,
                replacementAssignmentId
        )).isEqualTo("VIEWER");
    }

    @Test
    void consumedInvitationMerchantScopeCannotBeMutatedOrExtended() {
        UUID tenantId = insertTenant();
        UUID membershipId = UUID.randomUUID();
        UUID invitationId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        UUID intendedMerchantId = insertMerchant(tenantId);
        UUID additionalMerchantId = insertMerchant(tenantId);
        UUID userId = UUID.randomUUID();
        insertApplicationUser(userId);

        transactions.executeWithoutResult(status -> {
            insertInvitedMembership(membershipId, tenantId);
            insertInvitation(invitationId, membershipId, tenantId, "d3".repeat(32));
            jdbc.update(
                    """
                    INSERT INTO identity.invitation_grants
                        (invitation_id, assignment_id, tenant_id, role, scope_mode)
                    VALUES (?, ?, ?, 'MERCHANT_ADMIN', 'MERCHANT_SET')
                    """,
                    invitationId, assignmentId, tenantId
            );
            jdbc.update(
                    """
                    INSERT INTO identity.invitation_grant_merchant_scopes
                        (invitation_id, assignment_id, tenant_id, merchant_id)
                    VALUES (?, ?, ?, ?)
                    """,
                    invitationId, assignmentId, tenantId, intendedMerchantId
            );
        });
        transactions.executeWithoutResult(status -> consumeInvitation(
                membershipId, invitationId, userId
        ));

        assertThatThrownBy(() -> jdbc.update(
                """
                UPDATE identity.role_assignment_merchant_scopes
                   SET merchant_id = ?
                 WHERE role_assignment_id = ?
                   AND merchant_id = ?
                """,
                additionalMerchantId, assignmentId, intendedMerchantId
        )).isInstanceOf(Exception.class);

        assertThatThrownBy(() -> jdbc.update(
                """
                INSERT INTO identity.role_assignment_merchant_scopes
                    (role_assignment_id, merchant_id)
                VALUES (?, ?)
                """,
                assignmentId, additionalMerchantId
        )).isInstanceOf(Exception.class);
    }

    @Test
    void rejectsMembershipTenantChangesEvenWithoutMerchantScopes() {
        UUID userId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        UUID tenantId = insertTenant();
        UUID targetTenantId = insertTenant();
        Timestamp now = Timestamp.from(NOW);
        insertApplicationUser(userId);
        jdbc.update(
                """
                INSERT INTO identity.tenant_memberships
                    (id, application_user_id, tenant_id, status, version, created_at, updated_at)
                VALUES (?, ?, ?, 'ACTIVE', 0, ?, ?)
                """,
                membershipId, userId, tenantId, now, now
        );
        jdbc.update(
                """
                INSERT INTO identity.tenant_role_assignments
                    (id, membership_id, role, scope_mode)
                VALUES (?, ?, 'TENANT_ADMIN', 'TENANT_WIDE')
                """,
                UUID.randomUUID(), membershipId
        );

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE identity.tenant_memberships SET tenant_id = ? WHERE id = ?",
                targetTenantId, membershipId
        )).isInstanceOf(Exception.class);
    }

    @Test
    void requiresPendingVersionZeroWhenInvitationIsCreated() {
        UUID tenantId = insertTenant();

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            UUID membershipId = UUID.randomUUID();
            Timestamp now = Timestamp.from(NOW);
            insertInvitedMembership(membershipId, tenantId);
            jdbc.update(
                    """
                    INSERT INTO identity.invitations (
                        id, tenant_id, membership_id, intended_email, token_hash, status,
                        version, created_at, expires_at, consumed_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, 'CONSUMED', 1, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID(), tenantId, membershipId,
                    "consumed-" + membershipId + "@example.test", "2".repeat(64),
                    now, Timestamp.from(NOW.plus(7, ChronoUnit.DAYS)), now, now
            );
        })).isInstanceOf(Exception.class);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            UUID membershipId = UUID.randomUUID();
            Timestamp now = Timestamp.from(NOW);
            insertInvitedMembership(membershipId, tenantId);
            jdbc.update(
                    """
                    INSERT INTO identity.invitations (
                        id, tenant_id, membership_id, intended_email, token_hash, status,
                        version, created_at, expires_at, revoked_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, 'REVOKED', 1, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID(), tenantId, membershipId,
                    "revoked-" + membershipId + "@example.test", "3".repeat(64),
                    now, Timestamp.from(NOW.plus(7, ChronoUnit.DAYS)), now, now
            );
        })).isInstanceOf(Exception.class);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            UUID membershipId = UUID.randomUUID();
            Timestamp now = Timestamp.from(NOW);
            insertInvitedMembership(membershipId, tenantId);
            jdbc.update(
                    """
                    INSERT INTO identity.invitations (
                        id, tenant_id, membership_id, intended_email, token_hash, status,
                        version, created_at, expires_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, 'PENDING', 1, ?, ?, ?)
                    """,
                    UUID.randomUUID(), tenantId, membershipId,
                    "pending-" + membershipId + "@example.test", "4".repeat(64),
                    now, Timestamp.from(NOW.plus(7, ChronoUnit.DAYS)), now
            );
        })).isInstanceOf(Exception.class);
    }

    @Test
    void rejectsDuplicateProposedRolesAndSkippedInvitationVersions() {
        UUID tenantId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        UUID invitationId = UUID.randomUUID();
        insertTenant(tenantId);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            insertInvitedMembership(membershipId, tenantId);
            insertInvitation(invitationId, membershipId, tenantId, "e".repeat(64));
            jdbc.update(
                    """
                    INSERT INTO identity.invitation_grants
                        (invitation_id, assignment_id, tenant_id, role, scope_mode)
                    VALUES (?, ?, ?, 'TENANT_ADMIN', 'TENANT_WIDE'),
                           (?, ?, ?, 'TENANT_ADMIN', 'TENANT_WIDE')
                    """,
                    invitationId, UUID.randomUUID(), tenantId,
                    invitationId, UUID.randomUUID(), tenantId
            );
        })).isInstanceOf(Exception.class);

        insertTenantWideInvitation(tenantId, membershipId, invitationId, "f".repeat(64));
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            Timestamp revokedAt = Timestamp.from(NOW.plusSeconds(1));
            jdbc.update(
                    """
                    UPDATE identity.tenant_memberships
                       SET status = 'REVOKED', version = version + 1, updated_at = ?
                     WHERE id = ?
                    """,
                    revokedAt, membershipId
            );
            jdbc.update(
                    """
                    UPDATE identity.invitations
                       SET status = 'REVOKED', revoked_at = ?, version = version + 2,
                           updated_at = ?
                     WHERE id = ?
                    """,
                    revokedAt, revokedAt, invitationId
            );
        })).isInstanceOf(Exception.class);
    }

    @Test
    void rejectsActiveRoleAssignmentsForInvitedMemberships() {
        UUID tenantId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        insertTenantWideInvitation(
                tenantId, membershipId, UUID.randomUUID(), "1".repeat(64)
        );

        assertThatThrownBy(() -> jdbc.update(
                """
                INSERT INTO identity.tenant_role_assignments
                    (id, membership_id, role, scope_mode)
                VALUES (?, ?, 'TENANT_ADMIN', 'TENANT_WIDE')
                """,
                UUID.randomUUID(), membershipId
        )).isInstanceOf(Exception.class);
    }

    @Test
    void rejectsGrantIntentAddedAfterInvitationCreationTransaction() {
        UUID pendingTenantId = insertTenant();
        UUID pendingMembershipId = UUID.randomUUID();
        UUID pendingInvitationId = UUID.randomUUID();
        UUID pendingAssignmentId = UUID.randomUUID();
        UUID pendingMerchantId = insertMerchant(pendingTenantId);
        insertTenantWideInvitation(
                pendingTenantId, pendingMembershipId, pendingInvitationId, "7".repeat(64)
        );

        assertThatThrownBy(() -> jdbc.update(
                """
                INSERT INTO identity.invitation_grants
                    (invitation_id, assignment_id, tenant_id, role, scope_mode)
                VALUES (?, ?, ?, 'VIEWER', 'TENANT_WIDE')
                """,
                pendingInvitationId, UUID.randomUUID(), pendingTenantId
        )).isInstanceOf(Exception.class);

        transactions.executeWithoutResult(status -> {
            UUID scopedMembershipId = UUID.randomUUID();
            UUID scopedInvitationId = UUID.randomUUID();
            insertInvitedMembership(scopedMembershipId, pendingTenantId);
            insertInvitation(
                    scopedInvitationId, scopedMembershipId, pendingTenantId, "8".repeat(64)
            );
            jdbc.update(
                    """
                    INSERT INTO identity.invitation_grants
                        (invitation_id, assignment_id, tenant_id, role, scope_mode)
                    VALUES (?, ?, ?, 'MERCHANT_ADMIN', 'MERCHANT_SET')
                    """,
                    scopedInvitationId, pendingAssignmentId, pendingTenantId
            );
            jdbc.update(
                    """
                    INSERT INTO identity.invitation_grant_merchant_scopes
                        (invitation_id, assignment_id, tenant_id, merchant_id)
                    VALUES (?, ?, ?, ?)
                    """,
                    scopedInvitationId, pendingAssignmentId, pendingTenantId, pendingMerchantId
            );
        });

        UUID pendingSecondMerchantId = insertMerchant(pendingTenantId);
        UUID scopedInvitationId = jdbc.queryForObject(
                """
                SELECT invitation_id
                  FROM identity.invitation_grants
                 WHERE assignment_id = ?
                """,
                UUID.class,
                pendingAssignmentId
        );
        assertThatThrownBy(() -> jdbc.update(
                """
                INSERT INTO identity.invitation_grant_merchant_scopes
                    (invitation_id, assignment_id, tenant_id, merchant_id)
                VALUES (?, ?, ?, ?)
                """,
                scopedInvitationId, pendingAssignmentId, pendingTenantId, pendingSecondMerchantId
        )).isInstanceOf(Exception.class);

        UUID consumedTenantId = UUID.randomUUID();
        UUID consumedMembershipId = UUID.randomUUID();
        UUID consumedInvitationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        insertTenantWideInvitation(
                consumedTenantId, consumedMembershipId, consumedInvitationId, "2".repeat(64)
        );
        insertApplicationUser(userId);
        transactions.executeWithoutResult(status -> consumeInvitation(
                consumedMembershipId, consumedInvitationId, userId
        ));

        assertThatThrownBy(() -> jdbc.update(
                """
                INSERT INTO identity.invitation_grants
                    (invitation_id, assignment_id, tenant_id, role, scope_mode)
                VALUES (?, ?, ?, 'VIEWER', 'TENANT_WIDE')
                """,
                consumedInvitationId, UUID.randomUUID(), consumedTenantId
        )).isInstanceOf(Exception.class);

        UUID revokedTenantId = insertTenant();
        UUID revokedMembershipId = UUID.randomUUID();
        UUID revokedInvitationId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        UUID firstMerchantId = insertMerchant(revokedTenantId);
        transactions.executeWithoutResult(status -> {
            insertInvitedMembership(revokedMembershipId, revokedTenantId);
            insertInvitation(
                    revokedInvitationId, revokedMembershipId, revokedTenantId, "3".repeat(64)
            );
            jdbc.update(
                    """
                    INSERT INTO identity.invitation_grants
                        (invitation_id, assignment_id, tenant_id, role, scope_mode)
                    VALUES (?, ?, ?, 'MERCHANT_ADMIN', 'MERCHANT_SET')
                    """,
                    revokedInvitationId, assignmentId, revokedTenantId
            );
            jdbc.update(
                    """
                    INSERT INTO identity.invitation_grant_merchant_scopes
                        (invitation_id, assignment_id, tenant_id, merchant_id)
                    VALUES (?, ?, ?, ?)
                    """,
                    revokedInvitationId, assignmentId, revokedTenantId, firstMerchantId
            );
        });
        transactions.executeWithoutResult(status -> revokeInvitation(
                revokedMembershipId, revokedInvitationId
        ));
        UUID secondMerchantId = insertMerchant(revokedTenantId);

        assertThatThrownBy(() -> jdbc.update(
                """
                INSERT INTO identity.invitation_grant_merchant_scopes
                    (invitation_id, assignment_id, tenant_id, merchant_id)
                VALUES (?, ?, ?, ?)
                """,
                revokedInvitationId, assignmentId, revokedTenantId, secondMerchantId
        )).isInstanceOf(Exception.class);
    }

    @Test
    void rejectsSkippedMembershipVersionsAndTerminalMembershipMutation() {
        UUID tenantId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        UUID invitationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        insertTenantWideInvitation(tenantId, membershipId, invitationId, "4".repeat(64));
        insertApplicationUser(userId);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            Timestamp acceptedAt = Timestamp.from(NOW.plusSeconds(1));
            jdbc.update(
                    """
                    UPDATE identity.tenant_memberships
                       SET application_user_id = ?, status = 'ACTIVE', version = version + 2,
                           updated_at = ?
                     WHERE id = ?
                    """,
                    userId, acceptedAt, membershipId
            );
            jdbc.update(
                    """
                    UPDATE identity.invitations
                       SET status = 'CONSUMED', consumed_at = ?, version = version + 1,
                           updated_at = ?
                     WHERE id = ?
                    """,
                    acceptedAt, acceptedAt, invitationId
            );
        })).isInstanceOf(Exception.class);

        UUID revokedMembershipId = UUID.randomUUID();
        UUID revokedInvitationId = UUID.randomUUID();
        insertTenantWideInvitation(
                UUID.randomUUID(), revokedMembershipId, revokedInvitationId, "5".repeat(64)
        );
        transactions.executeWithoutResult(status -> revokeInvitation(
                revokedMembershipId, revokedInvitationId
        ));

        assertThatThrownBy(() -> jdbc.update(
                """
                UPDATE identity.tenant_memberships
                   SET application_user_id = ?, version = version + 1, updated_at = ?
                 WHERE id = ?
                """,
                userId, Timestamp.from(NOW.plusSeconds(2)), revokedMembershipId
        )).isInstanceOf(Exception.class);
    }

    @Test
    void rejectsInvitationMerchantScopeOwnedByAnotherTenant() {
        UUID invitationTenantId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        UUID invitationId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        UUID merchantTenantId = UUID.randomUUID();

        insertTenant(invitationTenantId);
        insertTenant(merchantTenantId);
        insertMerchant(merchantId, merchantTenantId);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            insertInvitedMembership(membershipId, invitationTenantId);
            insertInvitation(invitationId, membershipId, invitationTenantId, "d".repeat(64));
            jdbc.update(
                    """
                    INSERT INTO identity.invitation_grants
                        (invitation_id, assignment_id, tenant_id, role, scope_mode)
                    VALUES (?, ?, ?, 'MERCHANT_ADMIN', 'MERCHANT_SET')
                    """,
                    invitationId, assignmentId, invitationTenantId
            );
            jdbc.update(
                    """
                    INSERT INTO identity.invitation_grant_merchant_scopes
                        (invitation_id, assignment_id, tenant_id, merchant_id)
                    VALUES (?, ?, ?, ?)
                    """,
                    invitationId, assignmentId, invitationTenantId, merchantId
            );
        })).isInstanceOf(Exception.class);
    }

    @Test
    void expandsOutboxProducerConstraintOnlyForApprovedSliceTwoBProducers() {
        String definition = jdbc.queryForObject(
                """
                SELECT pg_get_constraintdef(oid)
                  FROM pg_constraint
                 WHERE conrelid = 'messaging.outbox'::regclass
                   AND conname = 'ck_outbox_producer'
                """,
                String.class
        );

        assertThat(definition)
                .contains("payment", "provider", "tenancy", "merchant", "identity")
                .contains("risk", "reconciliation", "casework");
    }

    private boolean tableExists(String tableName) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                      FROM information_schema.tables
                     WHERE table_schema = 'identity'
                       AND table_name = ?
                )
                """,
                Boolean.class,
                tableName
        ));
    }

    private void insertTenantWideInvitation(
            UUID tenantId,
            UUID membershipId,
            UUID invitationId,
            String tokenHash
    ) {
        ensureTenantExists(tenantId);
        transactions.executeWithoutResult(status -> {
            insertInvitedMembership(membershipId, tenantId);
            insertInvitation(invitationId, membershipId, tenantId, tokenHash);
            jdbc.update(
                    """
                    INSERT INTO identity.invitation_grants
                        (invitation_id, assignment_id, tenant_id, role, scope_mode)
                    VALUES (?, ?, ?, 'TENANT_ADMIN', 'TENANT_WIDE')
                    """,
                    invitationId, UUID.randomUUID(), tenantId
            );
        });
    }

    private void insertInvitedMembership(UUID membershipId, UUID tenantId) {
        Timestamp now = Timestamp.from(NOW);
        jdbc.update(
                """
                INSERT INTO identity.tenant_memberships
                    (id, application_user_id, tenant_id, status, version, created_at, updated_at)
                VALUES (?, NULL, ?, 'INVITED', 0, ?, ?)
                """,
                membershipId, tenantId, now, now
        );
    }

    private void insertInvitation(
            UUID invitationId,
            UUID membershipId,
            UUID tenantId,
            String tokenHash
    ) {
        Timestamp now = Timestamp.from(NOW);
        jdbc.update(
                """
                INSERT INTO identity.invitations (
                    id, tenant_id, membership_id, intended_email, token_hash, status,
                    version, created_at, expires_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 'PENDING', 0, ?, ?, ?)
                """,
                invitationId,
                tenantId,
                membershipId,
                "admin-" + invitationId + "@example.test",
                tokenHash,
                now,
                Timestamp.from(NOW.plus(7, ChronoUnit.DAYS)),
                now
        );
    }

    private void insertApplicationUser(UUID userId) {
        Timestamp now = Timestamp.from(NOW);
        jdbc.update(
                """
                INSERT INTO identity.application_users
                    (id, issuer, subject, status, version, created_at, updated_at)
                VALUES (?, ?, ?, 'ACTIVE', 0, ?, ?)
                """,
                userId, "issuer-" + userId, "subject-" + userId, now, now
        );
    }

    private void consumeInvitation(UUID membershipId, UUID invitationId, UUID userId) {
        Instant changedAt = NOW.plusSeconds(1);
        activateMembership(membershipId, userId, changedAt);
        copyInvitationGrants(invitationId, membershipId);
        consumeInvitationRecord(invitationId, changedAt);
    }

    private void activateMembership(UUID membershipId, UUID userId, Instant changedAt) {
        jdbc.update(
                """
                UPDATE identity.tenant_memberships
                   SET application_user_id = ?, status = 'ACTIVE', version = version + 1,
                       updated_at = ?
                 WHERE id = ?
                """,
                userId, Timestamp.from(changedAt), membershipId
        );
    }

    private void copyInvitationGrants(UUID invitationId, UUID membershipId) {
        jdbc.update(
                """
                INSERT INTO identity.tenant_role_assignments
                    (id, membership_id, role, scope_mode)
                SELECT assignment_id, ?, role, scope_mode
                  FROM identity.invitation_grants
                 WHERE invitation_id = ?
                """,
                membershipId, invitationId
        );
        jdbc.update(
                """
                INSERT INTO identity.role_assignment_merchant_scopes
                    (role_assignment_id, merchant_id)
                SELECT assignment_id, merchant_id
                  FROM identity.invitation_grant_merchant_scopes
                 WHERE invitation_id = ?
                """,
                invitationId
        );
    }

    private void consumeInvitationRecord(UUID invitationId, Instant changedAt) {
        jdbc.update(
                """
                UPDATE identity.invitations
                   SET status = 'CONSUMED', consumed_at = ?, version = version + 1,
                       updated_at = ?
                 WHERE id = ?
                """,
                Timestamp.from(changedAt), Timestamp.from(changedAt), invitationId
        );
    }

    private void revokeInvitation(UUID membershipId, UUID invitationId) {
        Timestamp changedAt = Timestamp.from(NOW.plusSeconds(1));
        jdbc.update(
                """
                UPDATE identity.tenant_memberships
                   SET status = 'REVOKED', version = version + 1, updated_at = ?
                 WHERE id = ?
                """,
                changedAt, membershipId
        );
        jdbc.update(
                """
                UPDATE identity.invitations
                   SET status = 'REVOKED', revoked_at = ?, version = version + 1,
                       updated_at = ?
                 WHERE id = ?
                """,
                changedAt, changedAt, invitationId
        );
    }

    private void updateMembershipStatus(UUID membershipId, String status, Instant changedAt) {
        jdbc.update(
                """
                UPDATE identity.tenant_memberships
                   SET status = ?, version = version + 1, updated_at = ?
                 WHERE id = ?
                """,
                status, Timestamp.from(changedAt), membershipId
        );
    }

    private UUID insertTenant() {
        UUID tenantId = UUID.randomUUID();
        insertTenant(tenantId);
        return tenantId;
    }

    private void insertTenant(UUID tenantId) {
        Timestamp now = Timestamp.from(NOW);
        jdbc.update(
                """
                INSERT INTO tenancy.tenants
                    (id, name, default_currency, default_locale, status,
                     version, created_at, updated_at)
                VALUES (?, ?, 'SAR', 'en-SA', 'PENDING_ACTIVATION', 0, ?, ?)
                """,
                tenantId, "Invitation Tenant " + tenantId, now, now
        );
    }

    private void ensureTenantExists(UUID tenantId) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM tenancy.tenants WHERE id = ?)",
                Boolean.class,
                tenantId
        );
        if (!Boolean.TRUE.equals(exists)) {
            insertTenant(tenantId);
        }
    }

    private UUID insertMerchant(UUID tenantId) {
        UUID merchantId = UUID.randomUUID();
        insertMerchant(merchantId, tenantId);
        return merchantId;
    }

    private void insertMerchant(UUID merchantId, UUID tenantId) {
        Timestamp now = Timestamp.from(NOW);
        jdbc.update(
                """
                INSERT INTO merchant.merchants
                    (id, tenant_id, name, status, version, created_at, updated_at)
                VALUES (?, ?, ?, 'ACTIVE', 0, ?, ?)
                """,
                merchantId, tenantId, "Invitation Merchant " + merchantId, now, now
        );
    }
}
