package com.ledgerops.identity.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvitationAndMembershipTests {
    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void invitationExpiresAtSevenDaysAndAcceptsOnlyVerifiedMatchingEmail() {
        Invitation invitation = invitation(InvitationId.newId(), UUID.randomUUID(),
                "person@example.com");
        InvitationAcceptance matching = new InvitationAcceptance(
                ApplicationUserId.newId(), "PERSON@example.com");

        assertThat(invitation.expiresAt()).isEqualTo(CREATED.plus(Invitation.VALIDITY));
        assertThat(invitation.consume(matching, invitation.expiresAt().minusNanos(1)).status())
                .isEqualTo(InvitationStatus.CONSUMED);
        assertThatThrownBy(() -> invitation.consume(matching, invitation.expiresAt()))
                .isExactlyInstanceOf(InvitationExpiredException.class);
        assertThatThrownBy(() -> invitation.consume(new InvitationAcceptance(
                ApplicationUserId.newId(), "someone-else@example.com"), CREATED))
                .isExactlyInstanceOf(InvalidInvitationException.class)
                .hasMessage("Verified email does not match invitation");
    }

    @Test
    void invitationRevocationAndConsumptionHaveTypedTerminalFailures() {
        Invitation revoked = invitation(InvitationId.newId(), UUID.randomUUID(),
                "person@example.com").revoke();
        assertThatThrownBy(() -> revoked.consume(acceptance("person@example.com"), CREATED))
                .isExactlyInstanceOf(InvitationRevokedException.class);
        assertThatThrownBy(revoked::revoke)
                .isExactlyInstanceOf(InvitationRevokedException.class);

        Invitation consumed = invitation(InvitationId.newId(), UUID.randomUUID(),
                "person@example.com").consume(acceptance("person@example.com"), CREATED);
        assertThatThrownBy(() -> consumed.consume(acceptance("person@example.com"), CREATED))
                .isExactlyInstanceOf(InvitationAlreadyConsumedException.class);
        assertThatThrownBy(consumed::revoke)
                .isExactlyInstanceOf(InvitationAlreadyConsumedException.class);
    }

    @Test
    void tokenRepresentationExposesOnlyItsHash() {
        InvitationTokenHash hash = new InvitationTokenHash("sha256:only-a-hash");
        Invitation invitation = invitation(InvitationId.newId(), UUID.randomUUID(),
                "person@example.com", hash);

        assertThat(invitation.tokenHash()).isEqualTo(hash);
    }

    @Test
    void reinvitationCreatesDistinctInvitationAndMembershipHistory() {
        UUID tenant = UUID.randomUUID();
        TenantMembership firstMembership = invitedMembership(TenantMembershipId.newId(), tenant);
        Invitation firstInvitation = invitation(InvitationId.newId(), tenant,
                "person@example.com");
        TenantMembership revokedMembership = firstMembership.revoke();
        Invitation revokedInvitation = firstInvitation.revoke();

        TenantMembership nextMembership = revokedMembership.reinvite(
                TenantMembershipId.newId(), assignments(tenant));
        Invitation nextInvitation = invitation(InvitationId.newId(), tenant,
                "person@example.com");

        assertThat(revokedMembership.status()).isEqualTo(TenantMembershipStatus.REVOKED);
        assertThat(revokedInvitation.status()).isEqualTo(InvitationStatus.REVOKED);
        assertThat(nextMembership.id()).isNotEqualTo(firstMembership.id());
        assertThat(nextInvitation.id()).isNotEqualTo(firstInvitation.id());
        assertThat(nextMembership.status()).isEqualTo(TenantMembershipStatus.INVITED);
        assertThat(nextInvitation.status()).isEqualTo(InvitationStatus.PENDING);
        assertThat(revokedMembership.status()).isEqualTo(TenantMembershipStatus.REVOKED);
        assertThatThrownBy(() -> revokedMembership.reinvite(
                revokedMembership.id(), assignments(tenant)))
                .isExactlyInstanceOf(InvalidMembershipTransitionException.class);
        assertThatThrownBy(() -> nextMembership.reinvite(
                TenantMembershipId.newId(), assignments(tenant)))
                .isExactlyInstanceOf(InvalidMembershipTransitionException.class);
    }

    @Test
    void allowsEveryApprovedMembershipTransition() {
        UUID tenant = UUID.randomUUID();
        TenantMembership invited = invitedMembership(TenantMembershipId.newId(), tenant);
        TenantMembership accepted = invited.accept(ApplicationUserId.newId());

        assertThat(accepted.status()).isEqualTo(TenantMembershipStatus.ACTIVE);
        assertThat(invited.revoke().status()).isEqualTo(TenantMembershipStatus.REVOKED);
        assertThat(accepted.suspend().status()).isEqualTo(TenantMembershipStatus.SUSPENDED);
        assertThat(accepted.revoke().status()).isEqualTo(TenantMembershipStatus.REVOKED);
        assertThat(accepted.suspend().activate().status()).isEqualTo(TenantMembershipStatus.ACTIVE);
        assertThat(accepted.suspend().revoke().status()).isEqualTo(TenantMembershipStatus.REVOKED);
    }

    @Test
    void rejectsEveryUnapprovedMembershipTransitionAndKeepsRevocationTerminal() {
        UUID tenant = UUID.randomUUID();
        TenantMembership invited = invitedMembership(TenantMembershipId.newId(), tenant);
        TenantMembership active = invited.accept(ApplicationUserId.newId());
        TenantMembership suspended = active.suspend();
        TenantMembership revoked = active.revoke();

        assertInvalid(invited::activate);
        assertInvalid(invited::suspend);
        assertInvalid(() -> active.accept(ApplicationUserId.newId()));
        assertInvalid(active::activate);
        assertInvalid(suspended::suspend);
        assertInvalid(() -> suspended.accept(ApplicationUserId.newId()));
        assertInvalid(revoked::activate);
        assertInvalid(revoked::suspend);
        assertInvalid(revoked::revoke);
        assertInvalid(() -> revoked.accept(ApplicationUserId.newId()));
    }

    @Test
    void controlledFactoriesEnforceMembershipLinkage() {
        UUID tenant = UUID.randomUUID();
        Set<TenantRoleAssignment> assignments = viewerAssignments(tenant);

        TenantMembership invited = TenantMembership.invited(
                TenantMembershipId.newId(), tenant, assignments);
        ApplicationUserId userId = ApplicationUserId.newId();
        TenantMembership active = invited.accept(userId);
        TenantMembership suspended = active.suspend();

        assertThat(invited.applicationUserId()).isNull();
        assertThatThrownBy(() -> TenantMembership.active(
                TenantMembershipId.newId(), tenant, null, assignments))
                .isInstanceOf(NullPointerException.class);
        assertThat(active.applicationUserId()).isEqualTo(userId);
        assertThat(suspended.applicationUserId()).isEqualTo(userId);
        assertThat(suspended.activate().applicationUserId()).isEqualTo(userId);
        assertThat(active.revoke().applicationUserId()).isEqualTo(userId);
        assertThat(invited.revoke().applicationUserId()).isNull();
    }

    @Test
    void protectsLastActiveAdminForSuspensionAndRevocation() {
        UUID tenant = UUID.randomUUID();
        TenantMembership admin = activeAdmin(TenantMembershipId.newId(), tenant);

        assertThatThrownBy(admin::suspend)
                .isExactlyInstanceOf(InvalidMembershipTransitionException.class);
        assertThatThrownBy(admin::revoke)
                .isExactlyInstanceOf(InvalidMembershipTransitionException.class);
        assertThatThrownBy(() -> admin.suspend(
                Set.of(admin), TenantAdminRemovalContext.MEMBERSHIP_CHANGE))
                .isExactlyInstanceOf(LastActiveTenantAdminException.class);
        assertThatThrownBy(() -> admin.revoke(
                Set.of(admin), TenantAdminRemovalContext.MEMBERSHIP_CHANGE))
                .isExactlyInstanceOf(LastActiveTenantAdminException.class);
    }

    @Test
    void allowsAdminRemovalWhenAnotherActiveAdminRemains() {
        UUID tenant = UUID.randomUUID();
        TenantMembership target = activeAdmin(TenantMembershipId.newId(), tenant);
        TenantMembership other = activeAdmin(TenantMembershipId.newId(), tenant);

        assertThat(target.suspend(Set.of(target, other),
                TenantAdminRemovalContext.MEMBERSHIP_CHANGE).status())
                .isEqualTo(TenantMembershipStatus.SUSPENDED);
        assertThat(target.revoke(Set.of(target, other),
                TenantAdminRemovalContext.MEMBERSHIP_CHANGE).status())
                .isEqualTo(TenantMembershipStatus.REVOKED);
    }

    @Test
    void preservesInitialMembershipIdentityWhenInvitationIsAccepted() {
        UUID tenant = UUID.randomUUID();
        TenantMembership invited = TenantMembership.invitedInitial(
                TenantMembershipId.newId(), tenant, Set.of());

        TenantMembership accepted = invited.accept(
                ApplicationUserId.newId(), viewerAssignments(tenant));
        TenantMembership suspended = accepted.suspend();

        assertThat(invited.initial()).isTrue();
        assertThat(accepted.initial()).isTrue();
        assertThat(suspended.initial()).isTrue();
        assertThat(accepted.revoke().initial()).isTrue();
    }

    @Test
    void rejectsCrossTenantAdminFactsInsteadOfCountingThem() {
        UUID tenant = UUID.randomUUID();
        TenantMembership target = activeAdmin(TenantMembershipId.newId(), tenant);
        TenantMembership otherTenantAdmin = activeAdmin(
                TenantMembershipId.newId(), UUID.randomUUID());

        assertThatThrownBy(() -> target.suspend(
                Set.of(target, otherTenantAdmin), TenantAdminRemovalContext.MEMBERSHIP_CHANGE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDuplicateMembershipFactsInsteadOfCountingOneMembershipTwice() {
        UUID tenant = UUID.randomUUID();
        TenantMembershipId membershipId = TenantMembershipId.newId();
        TenantMembership target = activeAdmin(membershipId, tenant);
        TenantMembership duplicateSnapshot = activeAdmin(membershipId, tenant);

        assertThatThrownBy(() -> target.suspend(
                Set.of(target, duplicateSnapshot), TenantAdminRemovalContext.MEMBERSHIP_CHANGE))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Admin removal facts must contain unique membership identities");
        assertThatThrownBy(() -> target.revoke(
                Set.of(target, duplicateSnapshot), TenantAdminRemovalContext.MEMBERSHIP_CHANGE))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Admin removal facts must contain unique membership identities");
    }

    @Test
    void guardedAdminRemovalRequiresFactsContainingTheTarget() {
        UUID tenant = UUID.randomUUID();
        TenantMembership target = activeAdmin(TenantMembershipId.newId(), tenant);

        assertThatThrownBy(() -> target.revoke(
                Set.of(), TenantAdminRemovalContext.MEMBERSHIP_CHANGE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Admin removal facts must include the target membership");
    }

    @Test
    void onlySimultaneousPlatformSuspensionOrArchiveMayRemoveLastAdmin() {
        UUID tenant = UUID.randomUUID();
        TenantMembership admin = activeAdmin(TenantMembershipId.newId(), tenant);

        assertThat(admin.suspend(Set.of(admin),
                TenantAdminRemovalContext.PLATFORM_TENANT_SUSPENSION).status())
                .isEqualTo(TenantMembershipStatus.SUSPENDED);
        assertThat(admin.revoke(Set.of(admin),
                TenantAdminRemovalContext.PLATFORM_TENANT_ARCHIVAL).status())
                .isEqualTo(TenantMembershipStatus.REVOKED);
        assertThatThrownBy(() -> admin.revoke(
                Set.of(admin), TenantAdminRemovalContext.MEMBERSHIP_CHANGE))
                .isExactlyInstanceOf(LastActiveTenantAdminException.class);
        assertThatThrownBy(() -> admin.revoke(
                Set.of(admin), TenantAdminRemovalContext.PLATFORM_TENANT_SUSPENSION))
                .isExactlyInstanceOf(LastActiveTenantAdminException.class);
        assertThatThrownBy(() -> admin.suspend(
                Set.of(admin), TenantAdminRemovalContext.PLATFORM_TENANT_ARCHIVAL))
                .isExactlyInstanceOf(LastActiveTenantAdminException.class);

        assertThatThrownBy(() -> TenantMembership.validateAdminRemoval(
                admin, Set.of(admin), TenantMembershipStatus.ACTIVE,
                TenantAdminRemovalContext.PLATFORM_TENANT_SUSPENSION))
                .isExactlyInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invitationAndMembershipRejectAssignmentsFromAnotherTenant() {
        UUID tenant = UUID.randomUUID();
        Set<TenantRoleAssignment> foreignAssignments = assignments(UUID.randomUUID());

        assertThatThrownBy(() -> Invitation.create(
                InvitationId.newId(), tenant, "person@example.com",
                new InvitationTokenHash("sha256:only"), foreignAssignments, CREATED))
                .isExactlyInstanceOf(InvalidInvitationException.class);
        assertThatThrownBy(() -> TenantMembership.invited(
                TenantMembershipId.newId(), tenant, foreignAssignments))
                .isExactlyInstanceOf(InvalidMembershipTransitionException.class);
    }

    private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable transition) {
        assertThatThrownBy(transition)
                .isExactlyInstanceOf(InvalidMembershipTransitionException.class);
    }

    private InvitationAcceptance acceptance(String email) {
        return new InvitationAcceptance(ApplicationUserId.newId(), email);
    }

    private Invitation invitation(InvitationId id, UUID tenant, String email) {
        return invitation(id, tenant, email, new InvitationTokenHash("sha256:only-a-hash"));
    }

    private Invitation invitation(
            InvitationId id,
            UUID tenant,
            String email,
            InvitationTokenHash hash
    ) {
        return Invitation.create(id, tenant, email, hash, assignments(tenant), CREATED);
    }

    private TenantMembership invitedMembership(TenantMembershipId id, UUID tenant) {
        return TenantMembership.invited(id, tenant, viewerAssignments(tenant));
    }

    private TenantMembership activeAdmin(TenantMembershipId id, UUID tenant) {
        return TenantMembership.active(id, tenant, ApplicationUserId.newId(), assignments(tenant));
    }

    private Set<TenantRoleAssignment> assignments(UUID tenant) {
        return Set.of(TenantRoleAssignment.tenantWide(
                TenantRoleAssignmentId.newId(), tenant, TenantRole.TENANT_ADMIN));
    }

    private Set<TenantRoleAssignment> viewerAssignments(UUID tenant) {
        return Set.of(TenantRoleAssignment.tenantWide(
                TenantRoleAssignmentId.newId(), tenant, TenantRole.VIEWER));
    }
}
