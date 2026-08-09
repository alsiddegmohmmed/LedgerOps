package com.ledgerops.identity.application;

import com.ledgerops.identity.domain.ApplicationUser;
import com.ledgerops.identity.domain.ApplicationUserRepository;
import com.ledgerops.identity.domain.ApplicationUserStatus;
import com.ledgerops.identity.domain.Invitation;
import com.ledgerops.identity.domain.InvitationAcceptance;
import com.ledgerops.identity.domain.InvitationRepository;
import com.ledgerops.identity.domain.InvitationTokenHash;
import com.ledgerops.identity.domain.TenantMembership;
import com.ledgerops.identity.domain.TenantMembershipId;
import com.ledgerops.identity.domain.TenantMembershipRepository;
import com.ledgerops.audit.api.AuditAppendPort;
import com.ledgerops.messaging.api.MessageOutbox;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class InvitationAcceptanceService {

    private final InvitationRepository invitations;
    private final TenantMembershipRepository memberships;
    private final ApplicationUserRepository applicationUsers;
    private final AuditAppendPort auditAppendPort;
    private final MessageOutbox outbox;
    private final Clock clock;

    public InvitationAcceptanceService(
            InvitationRepository invitations,
            TenantMembershipRepository memberships,
            ApplicationUserRepository applicationUsers,
            AuditAppendPort auditAppendPort,
            MessageOutbox outbox,
            Clock clock
    ) {
        this.invitations = invitations;
        this.memberships = memberships;
        this.applicationUsers = applicationUsers;
        this.auditAppendPort = auditAppendPort;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional
    public TenantMembership accept(
            InvitationTokenHash tokenHash,
            InvitationAcceptance acceptance,
            UUID correlationId
    ) {
        Invitation invitation = invitations.findPendingByTokenHashForUpdate(tokenHash)
                .orElseThrow(InvitationNotFoundException::new);
        TenantMembershipId membershipId = invitations.findMembershipId(invitation.id())
                .orElseThrow(InvitationNotFoundException::new);
        ApplicationUser applicationUser = applicationUsers.findById(acceptance.applicationUserId())
                .orElseThrow(UnknownApplicationIdentityException::new);
        if (applicationUser.status() == ApplicationUserStatus.DEACTIVATED) {
            throw new InactiveApplicationUserException();
        }

        TenantMembership invitedMembership = memberships.findById(membershipId)
                .orElseThrow(InvitationNotFoundException::new);

        Instant acceptedAt = clock.instant();
        Invitation consumed = invitation.consume(acceptance, acceptedAt);
        TenantMembership activeMembership = invitedMembership.accept(
                acceptance.applicationUserId(), invitation.proposedAssignments());

        TenantMembership persistedMembership = memberships.save(activeMembership);
        invitations.save(consumed, membershipId);
        auditAppendPort.appendIdentityMembershipAccepted(
                applicationUser.keycloakIdentity().issuer(),
                applicationUser.keycloakIdentity().subject(),
                invitation.tenantId(),
                membershipId.value(),
                applicationUser.id().value(),
                correlationId.toString()
        );
        outbox.appendOrGet(IdentityLifecycleOutboxFactory.invitationAccepted(
                invitation.tenantId(),
                membershipId.value(),
                invitation.id().value(),
                applicationUser.id().value(),
                persistedMembership.version(),
                correlationId,
                acceptedAt
        ));
        return persistedMembership;
    }
}
