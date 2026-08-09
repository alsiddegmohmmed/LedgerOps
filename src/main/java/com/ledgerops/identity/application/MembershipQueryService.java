package com.ledgerops.identity.application;

import com.ledgerops.identity.api.MembershipInvitationResponse;
import com.ledgerops.identity.api.MembershipNotFoundException;
import com.ledgerops.identity.api.MembershipQueryPort;
import com.ledgerops.identity.api.MembershipReadAuthorization;
import com.ledgerops.identity.api.MembershipResponse;
import com.ledgerops.identity.api.MembershipRoleResponse;
import com.ledgerops.identity.domain.Invitation;
import com.ledgerops.identity.domain.InvitationRepository;
import com.ledgerops.identity.domain.ScopeMode;
import com.ledgerops.identity.domain.TenantMembership;
import com.ledgerops.identity.domain.TenantMembershipRepository;
import com.ledgerops.identity.domain.TenantRoleAssignment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
class MembershipQueryService implements MembershipQueryPort {

    private final TenantMembershipRepository memberships;
    private final InvitationRepository invitations;

    MembershipQueryService(
            TenantMembershipRepository memberships,
            InvitationRepository invitations
    ) {
        this.memberships = memberships;
        this.invitations = invitations;
    }

    @Override
    @Transactional(readOnly = true)
    public List<MembershipResponse> current(
            UUID tenantId,
            MembershipReadAuthorization authorization
    ) {
        authorize(tenantId, authorization);
        return memberships.findAllByTenantId(tenantId).stream()
                .map(this::snapshot)
                .filter(snapshot -> visible(snapshot, authorization))
                .map(this::response)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public MembershipResponse current(
            UUID tenantId,
            UUID membershipId,
            MembershipReadAuthorization authorization
    ) {
        authorize(tenantId, authorization);
        TenantMembership membership = memberships.findById(
                        new com.ledgerops.identity.domain.TenantMembershipId(membershipId))
                .filter(candidate -> candidate.tenantId().equals(tenantId))
                .orElseThrow(MembershipNotFoundException::new);
        MembershipSnapshot snapshot = snapshot(membership);
        if (!visible(snapshot, authorization)) {
            throw new MembershipNotFoundException();
        }
        return response(snapshot);
    }

    private MembershipSnapshot snapshot(TenantMembership membership) {
        return new MembershipSnapshot(
                membership,
                invitations.findByMembershipId(membership.id()).orElse(null)
        );
    }

    private boolean visible(
            MembershipSnapshot snapshot,
            MembershipReadAuthorization authorization
    ) {
        if (authorization.tenantWide()) {
            return true;
        }
        return assignments(snapshot).stream()
                .filter(assignment -> assignment.scopeMode() == ScopeMode.MERCHANT_SET)
                .map(assignment -> assignment.merchantScope().merchantIds())
                .anyMatch(merchantIds -> intersects(merchantIds, authorization.merchantIds()));
    }

    private MembershipResponse response(MembershipSnapshot snapshot) {
        TenantMembership membership = snapshot.membership();
        List<MembershipRoleResponse> roles = assignments(snapshot).stream()
                .sorted(Comparator
                        .comparing((TenantRoleAssignment assignment) -> assignment.role().name())
                        .thenComparing(assignment -> assignment.id().value()))
                .map(assignment -> new MembershipRoleResponse(
                        assignment.id().value(),
                        assignment.role().name(),
                        assignment.scopeMode().name(),
                        assignment.scopeMode() == ScopeMode.MERCHANT_SET
                                ? assignment.merchantScope().merchantIds().stream()
                                .sorted()
                                .toList()
                                : List.of()
                ))
                .toList();
        Invitation invitation = snapshot.invitation();
        return new MembershipResponse(
                membership.tenantId(),
                membership.id().value(),
                membership.status().name(),
                membership.version(),
                membership.initial(),
                membership.applicationUserId() != null,
                roles,
                invitation == null
                        ? null
                        : new MembershipInvitationResponse(
                        invitation.id().value(),
                        invitation.intendedEmail(),
                        invitation.status().name(),
                        invitation.expiresAt()
                )
        );
    }

    private Set<TenantRoleAssignment> assignments(MembershipSnapshot snapshot) {
        if (!snapshot.membership().roleAssignments().isEmpty()) {
            return snapshot.membership().roleAssignments();
        }
        return snapshot.invitation() == null
                ? Set.of()
                : snapshot.invitation().proposedAssignments();
    }

    private boolean intersects(Set<UUID> left, Set<UUID> right) {
        return left.stream().anyMatch(right::contains);
    }

    private void authorize(
            UUID tenantId,
            MembershipReadAuthorization authorization
    ) {
        if (!authorization.tenantId().equals(tenantId)) {
            throw new MembershipNotFoundException();
        }
    }

    private record MembershipSnapshot(
            TenantMembership membership,
            Invitation invitation
    ) {
    }
}
