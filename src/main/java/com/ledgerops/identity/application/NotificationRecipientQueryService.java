package com.ledgerops.identity.application;

import com.ledgerops.identity.api.NotificationCapability;
import com.ledgerops.identity.api.NotificationRecipient;
import com.ledgerops.identity.api.NotificationRecipientQueryPort;
import com.ledgerops.identity.domain.ApplicationUserRepository;
import com.ledgerops.identity.domain.ApplicationUserStatus;
import com.ledgerops.identity.domain.Permission;
import com.ledgerops.identity.domain.ScopeMode;
import com.ledgerops.identity.domain.TenantMembership;
import com.ledgerops.identity.domain.TenantMembershipRepository;
import com.ledgerops.identity.domain.TenantMembershipStatus;
import com.ledgerops.identity.domain.TenantRoleAssignment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves recipients from current Identity state. Notification is a derived
 * module, so a suspended membership or deactivated user is never selected.
 */
@Service
class NotificationRecipientQueryService implements NotificationRecipientQueryPort {

    private final TenantMembershipRepository memberships;
    private final ApplicationUserRepository users;

    NotificationRecipientQueryService(
            TenantMembershipRepository memberships,
            ApplicationUserRepository users
    ) {
        this.memberships = memberships;
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationRecipient> findRecipients(
            UUID tenantId,
            UUID merchantId,
            NotificationCapability capability
    ) {
        if (tenantId == null || capability == null) {
            throw new IllegalArgumentException("Tenant and capability are required");
        }
        Permission required = permission(capability);
        List<NotificationRecipient> result = new ArrayList<>();
        for (TenantMembership membership : memberships.findAllByTenantId(tenantId)) {
            if (membership.status() != TenantMembershipStatus.ACTIVE
                    || membership.applicationUserId() == null) {
                continue;
            }
            var user = users.findById(membership.applicationUserId()).orElse(null);
            if (user == null || user.status() != ApplicationUserStatus.ACTIVE) {
                continue;
            }

            Authority authority = authority(membership);
            if (merchantId == null) {
                if (!authority.hasAny(required)) {
                    continue;
                }
                if (!authority.tenantWide().isEmpty()) {
                    result.add(new NotificationRecipient(
                            membership.applicationUserId().value(), tenantId,
                            true, Set.of()));
                } else {
                    result.add(new NotificationRecipient(
                            membership.applicationUserId().value(), tenantId,
                            false, authority.merchantIdsFor(required)));
                }
            } else if (authority.hasPermission(required, merchantId)) {
                boolean tenantWide = authority.tenantWide().contains(required);
                result.add(new NotificationRecipient(
                        membership.applicationUserId().value(), tenantId,
                        tenantWide,
                        tenantWide ? Set.of() : authority.merchantIdsFor(required)));
            }
        }
        return List.copyOf(result);
    }

    private Authority authority(TenantMembership membership) {
        Set<Permission> tenantWide = new HashSet<>();
        Map<UUID, Set<Permission>> merchantPermissions = new HashMap<>();
        for (TenantRoleAssignment assignment : membership.roleAssignments()) {
            Set<Permission> permissions = assignment.permissions();
            if (assignment.scopeMode() == ScopeMode.TENANT_WIDE) {
                tenantWide.addAll(permissions);
            } else {
                for (UUID merchantId : assignment.merchantScope().merchantIds()) {
                    merchantPermissions.computeIfAbsent(merchantId, ignored -> new HashSet<>())
                            .addAll(permissions);
                }
            }
        }
        return new Authority(Set.copyOf(tenantWide), immutable(merchantPermissions));
    }

    private Map<UUID, Set<Permission>> immutable(Map<UUID, Set<Permission>> values) {
        Map<UUID, Set<Permission>> copy = new HashMap<>();
        values.forEach((merchantId, permissions) -> copy.put(merchantId, Set.copyOf(permissions)));
        return Map.copyOf(copy);
    }

    private static Permission permission(NotificationCapability capability) {
        return switch (capability) {
            case RISK_READ -> Permission.RISK_READ;
            case CASE_READ -> Permission.CASE_READ;
            case NOTIFICATION_READ -> Permission.NOTIFICATION_READ;
        };
    }

    private record Authority(
            Set<Permission> tenantWide,
            Map<UUID, Set<Permission>> merchantPermissions
    ) {
        boolean hasPermission(Permission permission, UUID merchantId) {
            return tenantWide.contains(permission)
                    || merchantPermissions.getOrDefault(merchantId, Set.of()).contains(permission);
        }

        boolean hasAny(Permission permission) {
            return tenantWide.contains(permission)
                    || merchantPermissions.values().stream()
                    .anyMatch(permissions -> permissions.contains(permission));
        }

        Set<UUID> merchantIdsFor(Permission permission) {
            return merchantPermissions.entrySet().stream()
                    .filter(entry -> entry.getValue().contains(permission))
                    .map(Map.Entry::getKey)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }
}
