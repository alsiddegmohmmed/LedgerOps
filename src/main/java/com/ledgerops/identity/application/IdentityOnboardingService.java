package com.ledgerops.identity.application;

import com.ledgerops.identity.api.IdentityOnboardingPort;
import com.ledgerops.identity.api.IdentityOnboardingResult;
import com.ledgerops.identity.api.InitialTenantAdminInvitationRequest;
import com.ledgerops.identity.domain.Invitation;
import com.ledgerops.identity.domain.InvitationId;
import com.ledgerops.identity.domain.InvitationTokenHash;
import com.ledgerops.identity.domain.TenantMembership;
import com.ledgerops.identity.domain.TenantMembershipId;
import com.ledgerops.identity.domain.TenantMembershipRepository;
import com.ledgerops.identity.domain.TenantRole;
import com.ledgerops.identity.domain.TenantRoleAssignment;
import com.ledgerops.identity.domain.TenantRoleAssignmentId;
import com.ledgerops.messaging.api.MessageOutbox;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;

@Service
class IdentityOnboardingService implements IdentityOnboardingPort {

    private final TenantMembershipRepository memberships;
    private final com.ledgerops.identity.domain.InvitationRepository invitations;
    private final MessageOutbox outbox;
    private final Clock clock;

    IdentityOnboardingService(
            TenantMembershipRepository memberships,
            com.ledgerops.identity.domain.InvitationRepository invitations,
            MessageOutbox outbox,
            Clock clock
    ) {
        this.memberships = memberships;
        this.invitations = invitations;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Override
    @Transactional
    public IdentityOnboardingResult createInitialTenantAdminInvitation(
            InitialTenantAdminInvitationRequest request
    ) {
        TenantRoleAssignment assignment = TenantRoleAssignment.tenantWide(
                TenantRoleAssignmentId.newId(),
                request.tenantId(),
                TenantRole.TENANT_ADMIN
        );
        TenantMembership membership = TenantMembership.invitedInitial(
                TenantMembershipId.newId(),
                request.tenantId(),
                Set.of()
        );
        Instant createdAt = clock.instant();
        Invitation invitation = Invitation.create(
                InvitationId.newId(),
                request.tenantId(),
                request.intendedEmail(),
                new InvitationTokenHash(request.tokenHash()),
                Set.of(assignment),
                createdAt
        );

        memberships.save(membership);
        invitations.save(invitation, membership.id());
        outbox.appendOrGet(IdentityLifecycleOutboxFactory.invitationCreated(
                request.tenantId(),
                membership.id().value(),
                invitation.id().value(),
                request.correlationId(),
                request.operationId(),
                createdAt
        ));
        return new IdentityOnboardingResult(
                membership.id().value(), invitation.id().value());
    }
}
