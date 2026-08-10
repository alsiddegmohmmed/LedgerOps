package com.ledgerops.identity.application;

import com.ledgerops.audit.api.AuditAppendPort;
import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.AuthorizationPermissionDeniedException;
import com.ledgerops.identity.api.AuthorizationResourceNotFoundException;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.identity.api.InvitationRevocationCommand;
import com.ledgerops.identity.api.InvitationRevocationPort;
import com.ledgerops.identity.api.InvitationRevocationResult;
import com.ledgerops.identity.domain.Invitation;
import com.ledgerops.identity.domain.InvitationRepository;
import com.ledgerops.identity.domain.InvitationStatus;
import com.ledgerops.identity.domain.InvalidInvitationException;
import com.ledgerops.identity.domain.ScopeMode;
import com.ledgerops.identity.domain.TenantAdminRemovalContext;
import com.ledgerops.identity.domain.TenantMembership;
import com.ledgerops.identity.domain.TenantMembershipId;
import com.ledgerops.identity.domain.TenantMembershipRepository;
import com.ledgerops.identity.domain.TenantMembershipStatus;
import com.ledgerops.messaging.api.MessageOutbox;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

@Service
public class InvitationRevocationService implements InvitationRevocationPort {

    private final InvitationRepository invitations;
    private final TenantMembershipRepository memberships;
    private final AuditAppendPort auditAppendPort;
    private final MessageOutbox outbox;
    private final Clock clock;

    public InvitationRevocationService(
            InvitationRepository invitations,
            TenantMembershipRepository memberships,
            AuditAppendPort auditAppendPort,
            MessageOutbox outbox,
            Clock clock
    ) {
        this.invitations = invitations;
        this.memberships = memberships;
        this.auditAppendPort = auditAppendPort;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Override
    @Transactional
    public InvitationRevocationResult revoke(InvitationRevocationCommand command) {
        requireAuthorized(command);
        if (!command.confirmation()) {
            throw new IllegalArgumentException("Invitation revocation requires explicit confirmation");
        }

        TenantMembershipId membershipId = new TenantMembershipId(command.membershipId());
        Invitation invitation = invitations.findByMembershipIdForUpdate(membershipId)
                .orElseThrow(() -> new com.ledgerops.identity.api.InvitationNotFoundException());
        if (!command.tenantId().equals(invitation.tenantId())
                || !visible(invitation, command.authorization())) {
            throw new AuthorizationResourceNotFoundException();
        }
        if (invitation.status() != InvitationStatus.PENDING) {
            try {
                invitation.revoke();
            } catch (InvalidInvitationException exception) {
                throw new com.ledgerops.identity.api.InvitationStateConflictException(
                        exception.getMessage());
            }
        }

        TenantMembership membership = memberships.findByIdForUpdate(membershipId)
                .filter(candidate -> candidate.tenantId().equals(command.tenantId()))
                .orElseThrow(() -> new com.ledgerops.identity.api.InvitationNotFoundException());
        if (membership.status() != TenantMembershipStatus.INVITED) {
            throw new com.ledgerops.identity.api.InvitationStateConflictException(
                    "Only an invited membership can have its invitation revoked");
        }

        Instant revokedAt = clock.instant();
        TenantMembership revokedMembership = membership.revoke(
                Set.of(membership), TenantAdminRemovalContext.MEMBERSHIP_CHANGE);
        Invitation revokedInvitation;
        try {
            revokedInvitation = invitation.revoke();
        } catch (InvalidInvitationException exception) {
            throw new com.ledgerops.identity.api.InvitationStateConflictException(
                    exception.getMessage());
        }
        TenantMembership persistedMembership = memberships.save(revokedMembership);
        invitations.save(revokedInvitation, membership.id());

        AuthenticatedPrincipal actor = command.actor();
        AuthorizedRequestContext authorization = command.authorization();
        auditAppendPort.appendIdentityInvitationRevoked(
                actor.issuer(),
                actor.subject(),
                command.tenantId(),
                membership.id().value(),
                invitation.id().value(),
                command.reason(),
                authorization.correlationId()
        );
        UUID correlationId = correlationId(authorization.correlationId());
        outbox.appendOrGet(IdentityLifecycleOutboxFactory.invitationRevoked(
                command.tenantId(),
                membership.id().value(),
                invitation.id().value(),
                persistedMembership.version(),
                correlationId,
                revokedAt
        ));

        return new InvitationRevocationResult(
                command.tenantId(),
                membership.id().value(),
                invitation.id().value(),
                persistedMembership.status().name(),
                revokedInvitation.status().name(),
                persistedMembership.version()
        );
    }

    private void requireAuthorized(InvitationRevocationCommand command) {
        AuthorizedRequestContext authorization = command.authorization();
        if (!command.tenantId().equals(authorization.tenantId())) {
            throw new AuthorizationResourceNotFoundException();
        }
        if (!authorization.isHuman() || !authorization.canManageMemberships()) {
            throw new AuthorizationPermissionDeniedException("tenant:membership-manage");
        }
    }

    private boolean visible(Invitation invitation, AuthorizedRequestContext authorization) {
        if (authorization.isTenantWide()) {
            return true;
        }
        return invitation.proposedAssignments().stream()
                .filter(assignment -> assignment.scopeMode() == ScopeMode.MERCHANT_SET)
                .map(assignment -> assignment.merchantScope().merchantIds())
                .anyMatch(merchantIds -> merchantIds.stream()
                        .anyMatch(authorization.merchantIds()::contains));
    }

    private UUID correlationId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
        }
    }
}
