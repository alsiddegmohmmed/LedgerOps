package com.ledgerops.identity.domain;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class RoleGrantPolicy {
    private RoleGrantPolicy() { }

    public static void validate(Collection<TenantRoleAssignment> activeActorAssignments,
                                TenantRoleAssignment requestedAssignment) {
        Objects.requireNonNull(activeActorAssignments, "Actor assignments must not be null");
        Objects.requireNonNull(requestedAssignment, "Requested assignment must not be null");
        if (activeActorAssignments.isEmpty()) {
            throw new GrantEscalationException("Actor must have active role assignments");
        }
        if (activeActorAssignments.stream()
                .anyMatch(assignment -> !requestedAssignment.tenantId().equals(assignment.tenantId()))) {
            throw new GrantEscalationException("All role assignments must belong to the same Tenant");
        }

        Set<TenantRole> grantRoles = activeActorAssignments.stream()
                .map(TenantRoleAssignment::role)
                .filter(role -> role == TenantRole.TENANT_ADMIN || role == TenantRole.MERCHANT_ADMIN)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (grantRoles.isEmpty()) {
            throw new GrantEscalationException("Actor role cannot grant memberships");
        }
        boolean tenantAdmin = grantRoles.contains(TenantRole.TENANT_ADMIN);
        if (!grantRoles.contains(TenantRole.TENANT_ADMIN)
                && !merchantAdminMayGrant(requestedAssignment.role())) {
            throw new GrantEscalationException("Merchant Admin cannot grant this role");
        }

        if (requestedAssignment.scopeMode() == ScopeMode.TENANT_WIDE) {
            if (!grantRoles.contains(TenantRole.TENANT_ADMIN)) {
                throw new GrantEscalationException(
                        "Merchant-scoped actor cannot grant Tenant-wide authority");
            }
            if (!tenantAdmin) {
                validateTenantWideAuthority(activeActorAssignments, requestedAssignment);
            }
        } else {
            for (UUID merchantId : requestedAssignment.merchantScope().merchantIds()) {
                if (!hasGrantAuthorityForMerchant(activeActorAssignments, merchantId)) {
                    throw new GrantEscalationException(
                            "Actor cannot manage roles for Merchant " + merchantId);
                }
                if (!tenantAdmin) {
                    validateMerchantAuthority(activeActorAssignments, requestedAssignment, merchantId);
                }
            }
        }
    }

    private static void validateTenantWideAuthority(
            Collection<TenantRoleAssignment> actorAssignments,
            TenantRoleAssignment requestedAssignment
    ) {
        EnumSet<Permission> effectivePermissions = EnumSet.noneOf(Permission.class);
        actorAssignments.stream()
                .filter(assignment -> assignment.scopeMode() == ScopeMode.TENANT_WIDE)
                .forEach(assignment -> effectivePermissions.addAll(assignment.permissions()));
        if (!effectivePermissions.containsAll(requestedAssignment.permissions())) {
            throw new GrantEscalationException("Grant exceeds actor Tenant-wide permissions");
        }
    }

    private static void validateMerchantAuthority(
            Collection<TenantRoleAssignment> actorAssignments,
            TenantRoleAssignment requestedAssignment,
            UUID merchantId
    ) {
        EnumSet<Permission> effectivePermissions = EnumSet.noneOf(Permission.class);
        actorAssignments.stream()
                .filter(assignment -> assignment.scopeMode() == ScopeMode.TENANT_WIDE
                        || assignment.merchantScope().merchantIds().contains(merchantId))
                .forEach(assignment -> effectivePermissions.addAll(assignment.permissions()));
        if (!effectivePermissions.containsAll(requestedAssignment.permissions())) {
            throw new GrantEscalationException(
                    "Grant exceeds actor permissions for Merchant " + merchantId);
        }
    }

    private static boolean merchantAdminMayGrant(TenantRole role) {
        return Set.of(TenantRole.MERCHANT_ADMIN, TenantRole.OPERATIONS_AGENT,
                TenantRole.RISK_ANALYST, TenantRole.AUDITOR, TenantRole.VIEWER,
                TenantRole.INTEGRATION_DEVELOPER).contains(role);
    }

    private static boolean hasGrantAuthorityForMerchant(
            Collection<TenantRoleAssignment> actorAssignments,
            UUID merchantId
    ) {
        return actorAssignments.stream().anyMatch(assignment ->
                assignment.role() == TenantRole.TENANT_ADMIN
                        || (assignment.role() == TenantRole.MERCHANT_ADMIN
                        && assignment.merchantScope().merchantIds().contains(merchantId)));
    }
}
