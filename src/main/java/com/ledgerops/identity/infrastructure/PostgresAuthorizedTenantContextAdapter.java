package com.ledgerops.identity.infrastructure;

import com.ledgerops.identity.application.AuthorizedTenantContext;
import com.ledgerops.identity.application.AuthorizedTenantContextPort;
import com.ledgerops.identity.domain.ApplicationUserId;
import com.ledgerops.identity.domain.Permission;
import com.ledgerops.identity.domain.PrincipalType;
import com.ledgerops.identity.domain.ScopeMode;
import com.ledgerops.identity.domain.TenantRole;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
class PostgresAuthorizedTenantContextAdapter implements AuthorizedTenantContextPort {

    private final SpringDataTenantMembershipRepository memberships;
    private final SpringDataServiceCredentialRepository serviceCredentials;

    PostgresAuthorizedTenantContextAdapter(
            SpringDataTenantMembershipRepository memberships,
            SpringDataServiceCredentialRepository serviceCredentials
    ) {
        this.memberships = memberships;
        this.serviceCredentials = serviceCredentials;
    }

    @Override
    public Optional<AuthorizedTenantContext> find(
            ApplicationUserId applicationUserId,
            PrincipalType principalType,
            String serviceClientId,
            UUID tenantId
    ) {
        if (principalType != PrincipalType.HUMAN) {
            if (principalType != PrincipalType.SERVICE || serviceClientId == null) {
                return Optional.empty();
            }
            return serviceCredentials.findActive(
                            applicationUserId.value(), serviceClientId, tenantId
                    )
                    .map(credential -> new AuthorizedTenantContext(
                            credential.tenantId(),
                            ScopeMode.MERCHANT_SET,
                            Set.of(credential.merchantId()),
                            Set.of(Permission.PAYMENT_CREATE),
                            credential.id()
                    ));
        }
        return memberships.findActiveByApplicationUserIdAndTenantId(applicationUserId.value(), tenantId)
                .filter(membership -> !membership.roleAssignments().isEmpty())
                .map(this::toAuthorizedTenantContext);
    }

    private AuthorizedTenantContext toAuthorizedTenantContext(TenantMembershipJpaEntity membership) {
        Set<Permission> permissions = new LinkedHashSet<>();
        Set<UUID> merchantIds = new LinkedHashSet<>();
        boolean merchantScoped = false;
        for (TenantRoleAssignmentJpaEntity assignment : membership.roleAssignments()) {
            TenantRole role = TenantRole.valueOf(assignment.role());
            ScopeMode scopeMode = ScopeMode.valueOf(assignment.scopeMode());
            role.validateScope(scopeMode);
            permissions.addAll(role.permissions());
            if (scopeMode == ScopeMode.MERCHANT_SET) {
                merchantScoped = true;
                merchantIds.addAll(assignment.merchantIds());
            }
        }
        // A mixed grant is narrowed to its Merchant scopes. This cannot widen access.
        ScopeMode scopeMode = merchantScoped ? ScopeMode.MERCHANT_SET : ScopeMode.TENANT_WIDE;
        return new AuthorizedTenantContext(membership.tenantId(), scopeMode, merchantIds, permissions, null);
    }
}
