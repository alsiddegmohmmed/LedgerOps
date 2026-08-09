package com.ledgerops.identity.infrastructure;

import com.ledgerops.identity.application.AuthorizedTenantContext;
import com.ledgerops.identity.application.AuthorizedTenantContextPort;
import com.ledgerops.identity.domain.ApplicationUserId;
import com.ledgerops.identity.domain.Permission;
import com.ledgerops.identity.domain.PrincipalType;
import com.ledgerops.identity.domain.ServiceCredential;
import com.ledgerops.identity.domain.ScopeMode;
import com.ledgerops.identity.domain.ServiceCredentialRepository;
import com.ledgerops.identity.domain.ServiceCredentialStatus;
import com.ledgerops.identity.domain.TenantRole;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
class PostgresAuthorizedTenantContextAdapter implements AuthorizedTenantContextPort {

    private final SpringDataTenantMembershipRepository memberships;
    private final ServiceCredentialRepository credentials;

    PostgresAuthorizedTenantContextAdapter(
            SpringDataTenantMembershipRepository memberships,
            ServiceCredentialRepository credentials
    ) {
        this.memberships = memberships;
        this.credentials = credentials;
    }

    @Override
    public Optional<AuthorizedTenantContext> find(
            ApplicationUserId applicationUserId,
            PrincipalType principalType,
            String serviceClientId,
            UUID tenantId
    ) {
        if (principalType == PrincipalType.SERVICE) {
            return findServiceCredential(serviceClientId);
        }
        if (principalType != PrincipalType.HUMAN || applicationUserId == null) {
            return Optional.empty();
        }
        return memberships.findActiveByApplicationUserIdAndTenantId(applicationUserId.value(), tenantId)
                .filter(membership -> !membership.roleAssignments().isEmpty())
                .map(this::toAuthorizedTenantContext);
    }

    private Optional<AuthorizedTenantContext> findServiceCredential(String serviceClientId) {
        if (serviceClientId == null || serviceClientId.isBlank()) {
            return Optional.empty();
        }
        return credentials.findByClientId(serviceClientId)
                .filter(credential -> credential.status() == ServiceCredentialStatus.ACTIVE)
                .map(this::toServiceAuthorizedTenantContext);
    }

    private AuthorizedTenantContext toServiceAuthorizedTenantContext(ServiceCredential credential) {
        return new AuthorizedTenantContext(
                credential.tenantId(),
                ScopeMode.MERCHANT_SET,
                Set.of(credential.merchantId()),
                Set.of(Permission.PAYMENT_CREATE),
                credential.id().value()
        );
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
