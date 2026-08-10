package com.ledgerops.identity.application;

import com.ledgerops.audit.api.AuditAppendPort;
import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.AuthorizationPermissionDeniedException;
import com.ledgerops.identity.api.AuthorizationResourceNotFoundException;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.identity.api.InvitationAdministrationCommand;
import com.ledgerops.identity.api.InvitationAdministrationPort;
import com.ledgerops.identity.api.InvitationAdministrationResult;
import com.ledgerops.identity.api.MembershipRoleAssignmentRequest;
import com.ledgerops.identity.api.MembershipRoleMutationCommand;
import com.ledgerops.identity.api.MembershipRoleMutationResult;
import com.ledgerops.identity.domain.Invitation;
import com.ledgerops.identity.domain.InvitationId;
import com.ledgerops.identity.domain.InvitationRepository;
import com.ledgerops.identity.domain.InvitationTokenHash;
import com.ledgerops.identity.domain.MerchantScope;
import com.ledgerops.identity.domain.RoleGrantPolicy;
import com.ledgerops.identity.domain.TenantAdminRemovalContext;
import com.ledgerops.identity.domain.TenantMembership;
import com.ledgerops.identity.domain.TenantMembershipId;
import com.ledgerops.identity.domain.TenantMembershipRepository;
import com.ledgerops.identity.domain.TenantMembershipStatus;
import com.ledgerops.identity.domain.TenantRoleAssignment;
import com.ledgerops.identity.domain.TenantRoleAssignmentId;
import com.ledgerops.merchant.domain.MerchantId;
import com.ledgerops.merchant.domain.MerchantRepository;
import com.ledgerops.messaging.api.MessageOutbox;
import com.ledgerops.tenancy.api.TenantReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class InvitationAdministrationService implements InvitationAdministrationPort {

    private final TenantMembershipRepository memberships;
    private final InvitationRepository invitations;
    private final MerchantRepository merchants;
    private final AuditAppendPort audit;
    private final MessageOutbox outbox;
    private final Clock clock;

    public InvitationAdministrationService(
            TenantMembershipRepository memberships,
            InvitationRepository invitations,
            MerchantRepository merchants,
            AuditAppendPort audit,
            MessageOutbox outbox,
            Clock clock
    ) {
        this.memberships = memberships;
        this.invitations = invitations;
        this.merchants = merchants;
        this.audit = audit;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Override
    @Transactional
    public InvitationAdministrationResult create(InvitationAdministrationCommand command) {
        requireAuthorized(command.tenantId(), command.authorization(), true);
        requireConfirmation(command.confirmation());
        Set<TenantRoleAssignment> assignments = assignments(command);
        TenantMembership actorMembership = actorMembership(
                command.tenantId(), command.authorization());
        validateGrants(actorMembership, assignments);

        TenantMembership membership = TenantMembership.invited(
                TenantMembershipId.newId(), command.tenantId(), Set.of());
        Instant createdAt = clock.instant();
        Invitation invitation = Invitation.create(
                InvitationId.newId(),
                command.tenantId(),
                command.intendedEmail(),
                new InvitationTokenHash(command.tokenHash()),
                assignments,
                createdAt
        );
        memberships.save(membership);
        invitations.save(invitation, membership.id());
        audit.appendIdentityInvitationCreated(
                command.actor().issuer(), command.actor().subject(), command.tenantId(),
                membership.id().value(), invitation.id().value(), command.reason(),
                command.authorization().correlationId()
        );
        outbox.appendOrGet(IdentityLifecycleOutboxFactory.invitationCreated(
                command.tenantId(), membership.id().value(), invitation.id().value(),
                command.correlationId(), command.operationId(), createdAt
        ));
        return result(membership, invitation);
    }

    @Override
    @Transactional
    public InvitationAdministrationResult reinvite(InvitationAdministrationCommand command) {
        requireAuthorized(command.tenantId(), command.authorization(), true);
        requireConfirmation(command.confirmation());
        if (command.revokedMembershipId() == null) {
            throw new IllegalArgumentException("Revoked membership ID is required for reinvitation");
        }
        Set<TenantRoleAssignment> assignments = assignments(command);
        TenantMembership actorMembership = actorMembership(
                command.tenantId(), command.authorization());
        validateGrants(actorMembership, assignments);

        TenantMembership previous = memberships.findByIdForUpdate(
                        new TenantMembershipId(command.revokedMembershipId()))
                .filter(candidate -> candidate.tenantId().equals(command.tenantId()))
                .orElseThrow(InvitationNotFoundException::new);
        if (previous.status() != TenantMembershipStatus.REVOKED) {
            throw new InvitationRevocationConflictException(
                    "Only a revoked membership can be reinvited");
        }
        TenantMembership membership = previous.reinvite(TenantMembershipId.newId(), Set.of());
        Instant createdAt = clock.instant();
        Invitation invitation = Invitation.create(
                InvitationId.newId(),
                command.tenantId(),
                command.intendedEmail(),
                new InvitationTokenHash(command.tokenHash()),
                assignments,
                createdAt
        );
        memberships.save(membership);
        invitations.save(invitation, membership.id());
        audit.appendIdentityInvitationReinvited(
                command.actor().issuer(), command.actor().subject(), command.tenantId(),
                previous.id().value(), membership.id().value(), invitation.id().value(),
                command.reason(), command.authorization().correlationId()
        );
        outbox.appendOrGet(IdentityLifecycleOutboxFactory.invitationCreated(
                command.tenantId(), membership.id().value(), invitation.id().value(),
                command.correlationId(), command.operationId(), createdAt
        ));
        return result(membership, invitation);
    }

    @Override
    @Transactional
    public MembershipRoleMutationResult replaceRoles(MembershipRoleMutationCommand command) {
        requireAuthorized(command.tenantId(), command.authorization(), true);
        requireConfirmation(command.confirmation());
        Set<TenantRoleAssignment> assignments = assignments(
                command.tenantId(), command.assignments());
        TenantMembership actorMembership = actorMembership(
                command.tenantId(), command.authorization());
        validateGrants(actorMembership, assignments);

        TenantMembership target = memberships.findByIdForUpdate(
                        new TenantMembershipId(command.membershipId()))
                .filter(candidate -> candidate.tenantId().equals(command.tenantId()))
                .orElseThrow(AuthorizationResourceNotFoundException::new);
        if (!visible(target, command.authorization())) {
            throw new AuthorizationResourceNotFoundException();
        }
        Set<TenantMembership> current = Set.copyOf(
                memberships.findAllByTenantId(command.tenantId()));
        TenantMembership changed = target.replaceRoleAssignments(
                assignments, current, TenantAdminRemovalContext.MEMBERSHIP_CHANGE);
        TenantMembership saved = memberships.save(changed);
        audit.appendIdentityMembershipRolesChanged(
                command.actor().issuer(), command.actor().subject(), command.tenantId(),
                saved.id().value(), command.reason(), command.authorization().correlationId()
        );
        outbox.appendOrGet(IdentityLifecycleOutboxFactory.roleAssignmentsChanged(
                command.tenantId(), saved.id().value(), saved.version(),
                command.correlationId(), command.operationId(), clock.instant()
        ));
        return new MembershipRoleMutationResult(
                command.tenantId(), saved.id().value(), saved.status().name(), saved.version());
    }

    private Set<TenantRoleAssignment> assignments(InvitationAdministrationCommand command) {
        return assignments(command.tenantId(), command.assignments());
    }

    private Set<TenantRoleAssignment> assignments(
            UUID tenantId,
            Set<MembershipRoleAssignmentRequest> requested
    ) {
        Set<TenantRoleAssignment> result = new LinkedHashSet<>();
        for (MembershipRoleAssignmentRequest request : requested) {
            MerchantScope scope = request.scopeMode() == com.ledgerops.identity.domain.ScopeMode.MERCHANT_SET
                    ? merchantScope(tenantId, request.merchantIds())
                    : null;
            TenantRoleAssignment assignment = request.scopeMode()
                    == com.ledgerops.identity.domain.ScopeMode.TENANT_WIDE
                    ? TenantRoleAssignment.tenantWide(
                    TenantRoleAssignmentId.newId(), tenantId, request.role())
                    : TenantRoleAssignment.merchantScoped(
                    TenantRoleAssignmentId.newId(), tenantId, request.role(), scope);
            result.add(assignment);
        }
        return Set.copyOf(result);
    }

    private MerchantScope merchantScope(UUID tenantId, Set<UUID> merchantIds) {
        Map<UUID, UUID> ownership = new LinkedHashMap<>();
        for (UUID merchantId : merchantIds) {
            merchants.findById(
                            TenantReference.from(tenantId), MerchantId.from(merchantId))
                    .orElseThrow(() -> new AuthorizationResourceNotFoundException());
            ownership.put(merchantId, tenantId);
        }
        return MerchantScope.validated(tenantId, merchantIds, ownership);
    }

    private void validateGrants(
            TenantMembership actor,
            Set<TenantRoleAssignment> assignments
    ) {
        for (TenantRoleAssignment assignment : assignments) {
            RoleGrantPolicy.validate(actor.roleAssignments(), assignment);
        }
    }

    private TenantMembership actorMembership(
            UUID tenantId,
            AuthorizedRequestContext authorization
    ) {
        if (authorization.applicationUserId() == null) {
            throw new AuthorizationPermissionDeniedException("tenant:membership-manage");
        }
        return memberships.findActiveByApplicationUserAndTenant(
                        new com.ledgerops.identity.domain.ApplicationUserId(
                                authorization.applicationUserId()), tenantId)
                .orElseThrow(() -> new AuthorizationPermissionDeniedException(
                        "tenant:membership-manage"));
    }

    private boolean visible(TenantMembership target, AuthorizedRequestContext authorization) {
        if (authorization.isTenantWide()) {
            return true;
        }
        return target.roleAssignments().stream().allMatch(assignment ->
                assignment.scopeMode() == com.ledgerops.identity.domain.ScopeMode.MERCHANT_SET
                        && assignment.merchantScope().merchantIds().stream()
                        .allMatch(authorization::allowsMerchant));
    }

    private void requireAuthorized(
            UUID tenantId,
            AuthorizedRequestContext authorization,
            boolean requireRoles
    ) {
        if (!tenantId.equals(authorization.tenantId())) {
            throw new AuthorizationResourceNotFoundException();
        }
        if (!authorization.isHuman() || !authorization.canManageMemberships()
                || (requireRoles && !authorization.canManageRoles())) {
            throw new AuthorizationPermissionDeniedException(
                    requireRoles ? "tenant:role-manage" : "tenant:membership-manage");
        }
    }

    private void requireConfirmation(boolean confirmation) {
        if (!confirmation) {
            throw new IllegalArgumentException(
                    "Invitation administration requires explicit confirmation");
        }
    }

    private InvitationAdministrationResult result(
            TenantMembership membership,
            Invitation invitation
    ) {
        return new InvitationAdministrationResult(
                membership.tenantId(), membership.id().value(), invitation.id().value(),
                membership.status().name(), invitation.status().name(), invitation.expiresAt(),
                membership.version()
        );
    }
}
