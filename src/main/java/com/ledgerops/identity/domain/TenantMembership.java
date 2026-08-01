package com.ledgerops.identity.domain;

import java.util.Objects;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class TenantMembership {
    private final TenantMembershipId id;
    private final UUID tenantId;
    private final ApplicationUserId applicationUserId;
    private final TenantMembershipStatus status;
    private final Set<TenantRoleAssignment> roleAssignments;

    private TenantMembership(TenantMembershipId id, UUID tenantId,
                             ApplicationUserId applicationUserId,
                             TenantMembershipStatus status,
                             Set<TenantRoleAssignment> roleAssignments) {
        this.id = Objects.requireNonNull(id, "Membership ID must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "Membership Tenant ID must not be null");
        this.applicationUserId = applicationUserId;
        this.status = Objects.requireNonNull(status, "Membership status must not be null");
        this.roleAssignments = Set.copyOf(Objects.requireNonNull(
                roleAssignments, "Role assignments must not be null"));
        if (this.roleAssignments.isEmpty()) {
            throw new InvalidMembershipTransitionException(
                    "Membership must have at least one role assignment");
        }
        for (TenantRoleAssignment assignment : this.roleAssignments) {
            if (!tenantId.equals(assignment.tenantId())) {
                throw new InvalidMembershipTransitionException("Role assignment belongs to another Tenant");
            }
        }
    }

    public static TenantMembership invited(TenantMembershipId id, UUID tenantId,
                                           Set<TenantRoleAssignment> roleAssignments) {
        return new TenantMembership(id, tenantId, null, TenantMembershipStatus.INVITED,
                roleAssignments);
    }

    public static TenantMembership active(TenantMembershipId id, UUID tenantId,
                                          ApplicationUserId applicationUserId,
                                          Set<TenantRoleAssignment> roleAssignments) {
        return new TenantMembership(id, tenantId,
                Objects.requireNonNull(applicationUserId,
                        "Active membership user ID must not be null"),
                TenantMembershipStatus.ACTIVE, roleAssignments);
    }

    public TenantMembership activate() {
        if (status != TenantMembershipStatus.SUSPENDED) {
            throw invalid(TenantMembershipStatus.ACTIVE);
        }
        if (applicationUserId == null) {
            throw new InvalidMembershipTransitionException(
                    "Suspended membership must remain linked to an application user");
        }
        return copy(TenantMembershipStatus.ACTIVE, applicationUserId);
    }

    public TenantMembership accept(ApplicationUserId acceptedUser) {
        if (status != TenantMembershipStatus.INVITED) {
            throw invalid(TenantMembershipStatus.ACTIVE);
        }
        return copy(TenantMembershipStatus.ACTIVE,
                Objects.requireNonNull(acceptedUser, "Accepted user must not be null"));
    }

    public TenantMembership suspend() {
        if (status != TenantMembershipStatus.ACTIVE) {
            throw invalid(TenantMembershipStatus.SUSPENDED);
        }
        requireGuardedAdminRemoval();
        return copy(TenantMembershipStatus.SUSPENDED, applicationUserId);
    }

    public TenantMembership suspend(Set<TenantMembership> currentMemberships,
                                    TenantAdminRemovalContext context) {
        if (status != TenantMembershipStatus.ACTIVE) {
            throw invalid(TenantMembershipStatus.SUSPENDED);
        }
        validateAdminRemoval(this, currentMemberships, TenantMembershipStatus.SUSPENDED, context);
        return copy(TenantMembershipStatus.SUSPENDED, applicationUserId);
    }

    public TenantMembership revoke() {
        if (status == TenantMembershipStatus.REVOKED) {
            throw invalid(TenantMembershipStatus.REVOKED);
        }
        requireGuardedAdminRemoval();
        return copy(TenantMembershipStatus.REVOKED, applicationUserId);
    }

    public TenantMembership revoke(Set<TenantMembership> currentMemberships,
                                   TenantAdminRemovalContext context) {
        if (status == TenantMembershipStatus.REVOKED) {
            throw invalid(TenantMembershipStatus.REVOKED);
        }
        validateAdminRemoval(this, currentMemberships, TenantMembershipStatus.REVOKED, context);
        return copy(TenantMembershipStatus.REVOKED, applicationUserId);
    }

    public TenantMembership reinvite(
            TenantMembershipId newId,
            Set<TenantRoleAssignment> proposedAssignments
    ) {
        if (status != TenantMembershipStatus.REVOKED) {
            throw new InvalidMembershipTransitionException(
                    "Only a revoked membership can be reinvited"
            );
        }
        if (id.equals(newId)) {
            throw new InvalidMembershipTransitionException(
                    "Reinvitation must create a new membership identity"
            );
        }
        return invited(newId, tenantId, proposedAssignments);
    }

    public static void validateAdminRemoval(TenantMembership target,
                                            Set<TenantMembership> currentMemberships,
                                            TenantMembershipStatus requestedStatus,
                                            TenantAdminRemovalContext context) {
        Objects.requireNonNull(target, "Target membership must not be null");
        Objects.requireNonNull(currentMemberships, "Current memberships must not be null");
        Objects.requireNonNull(requestedStatus, "Requested membership status must not be null");
        Objects.requireNonNull(context, "Admin removal context must not be null");
        if (requestedStatus != TenantMembershipStatus.SUSPENDED
                && requestedStatus != TenantMembershipStatus.REVOKED) {
            throw new IllegalArgumentException(
                    "Admin removal status must be SUSPENDED or REVOKED");
        }
        if (currentMemberships.stream().anyMatch(m -> !target.tenantId.equals(m.tenantId))) {
            throw new IllegalArgumentException(
                    "Admin removal facts must belong to one Tenant");
        }
        Set<TenantMembershipId> membershipIds = new HashSet<>();
        for (TenantMembership membership : currentMemberships) {
            if (!membershipIds.add(membership.id())) {
                throw new IllegalArgumentException(
                        "Admin removal facts must contain unique membership identities");
            }
        }
        if (currentMemberships.stream().filter(m -> target.id.equals(m.id)).count() != 1) {
            throw new IllegalArgumentException(
                    "Admin removal facts must include the target membership");
        }
        if (context.permitsLastAdminRemoval(requestedStatus)
                || target.status != TenantMembershipStatus.ACTIVE
                || !target.hasRole(TenantRole.TENANT_ADMIN)) {
            return;
        }
        long activeAdmins = currentMemberships.stream()
                .filter(m -> m.status == TenantMembershipStatus.ACTIVE)
                .filter(m -> m.hasRole(TenantRole.TENANT_ADMIN))
                .count();
        if (activeAdmins <= 1) {
            throw new LastActiveTenantAdminException();
        }
    }

    public boolean hasRole(TenantRole role) {
        return roleAssignments.stream().anyMatch(a -> a.role() == role);
    }

    public TenantMembershipId id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public ApplicationUserId applicationUserId() {
        return applicationUserId;
    }

    public TenantMembershipStatus status() {
        return status;
    }

    public Set<TenantRoleAssignment> roleAssignments() {
        return roleAssignments;
    }

    private TenantMembership copy(TenantMembershipStatus next, ApplicationUserId userId) {
        return new TenantMembership(id, tenantId, userId, next, roleAssignments);
    }

    private void requireGuardedAdminRemoval() {
        if (status == TenantMembershipStatus.ACTIVE && hasRole(TenantRole.TENANT_ADMIN)) {
            throw new InvalidMembershipTransitionException(
                    "Active Tenant Admin removal requires current membership facts and context");
        }
    }

    private InvalidMembershipTransitionException invalid(TenantMembershipStatus target) {
        return new InvalidMembershipTransitionException(
                "Membership cannot transition from " + status + " to " + target);
    }
}
