package com.ledgerops.identity.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Stage2ABoundaryTests {

    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void invitationExpiresAtExactlySevenDaysAndConsumesOnlyOnce() {
        UUID tenant = UUID.randomUUID();
        Invitation invitation = Invitation.create(
                InvitationId.newId(), tenant, "  PERSON@EXAMPLE.COM ",
                new InvitationTokenHash("sha256:digest"),
                Set.of(viewerAssignment(tenant)), CREATED);

        assertThat(invitation.intendedEmail()).isEqualTo("person@example.com");
        assertThat(invitation.expiresAt()).isEqualTo(CREATED.plus(Invitation.VALIDITY));
        assertThat(invitation.isExpired(invitation.expiresAt())).isTrue();

        Invitation consumed = invitation.consume(
                new InvitationAcceptance(ApplicationUserId.newId(), "PERSON@example.com"),
                invitation.expiresAt().minusNanos(1));
        assertThat(consumed.status()).isEqualTo(InvitationStatus.CONSUMED);
        assertThatThrownBy(() -> consumed.consume(
                new InvitationAcceptance(ApplicationUserId.newId(), "person@example.com"),
                invitation.expiresAt().minusNanos(2)))
                .isExactlyInstanceOf(InvitationAlreadyConsumedException.class);
    }

    @Test
    void invitationRejectsExpiredAndMismatchedVerifiedEmail() {
        UUID tenant = UUID.randomUUID();
        Invitation invitation = Invitation.create(
                InvitationId.newId(), tenant, "person@example.com",
                new InvitationTokenHash("sha256:digest"),
                Set.of(viewerAssignment(tenant)), CREATED);

        assertThatThrownBy(() -> invitation.consume(
                new InvitationAcceptance(ApplicationUserId.newId(), "other@example.com"),
                CREATED.plus(Invitation.VALIDITY).minusNanos(1)))
                .isExactlyInstanceOf(InvalidInvitationException.class);
        assertThatThrownBy(() -> invitation.consume(
                new InvitationAcceptance(ApplicationUserId.newId(), "person@example.com"),
                CREATED.plus(Invitation.VALIDITY)))
                .isExactlyInstanceOf(InvitationExpiredException.class);
    }

    @Test
    void roleAssignmentAndScopeSnapshotsAreImmutable() {
        UUID tenant = UUID.randomUUID();
        UUID merchant = UUID.randomUUID();
        MerchantScope scope = MerchantScope.validated(tenant, Set.of(merchant),
                java.util.Map.of(merchant, tenant));
        TenantRoleAssignment assignment = TenantRoleAssignment.merchantScoped(
                TenantRoleAssignmentId.newId(), tenant, TenantRole.VIEWER, scope);

        assertThatThrownBy(() -> assignment.merchantScope().merchantIds().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(assignment.coversMerchants(Set.of(merchant))).isTrue();
        assertThat(assignment.coversMerchants(Set.of(UUID.randomUUID()))).isFalse();
    }

    private TenantRoleAssignment viewerAssignment(UUID tenant) {
        return TenantRoleAssignment.tenantWide(
                TenantRoleAssignmentId.newId(), tenant, TenantRole.VIEWER);
    }
}
