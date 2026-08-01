package com.ledgerops.identity.domain;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantRoleTests {

    @Test
    void exposesExactlyTheApprovedClosedPermissionCatalogue() {
        assertThat(Set.of(Permission.values())).isEqualTo(Set.of(
                Permission.PLATFORM_TENANT_CREATE,
                Permission.PLATFORM_TENANT_ACTIVATE,
                Permission.PLATFORM_TENANT_SUSPEND,
                Permission.PLATFORM_TENANT_REACTIVATE,
                Permission.PLATFORM_TENANT_ARCHIVE,
                Permission.PLATFORM_PROVIDER_SCENARIO_MANAGE,
                Permission.PLATFORM_HEALTH_READ,
                Permission.PLATFORM_AUDIT_READ,
                Permission.SUPPORT_TENANT_READ,
                Permission.TENANT_READ,
                Permission.TENANT_CONFIGURE,
                Permission.TENANT_MEMBERSHIP_MANAGE,
                Permission.TENANT_ROLE_MANAGE,
                Permission.MERCHANT_CREATE,
                Permission.MERCHANT_READ,
                Permission.MERCHANT_CONFIGURE,
                Permission.MERCHANT_SUSPEND,
                Permission.CREDENTIAL_MANAGE,
                Permission.PAYMENT_READ,
                Permission.PAYMENT_NOTE_ADD,
                Permission.PAYMENT_RETRY,
                Permission.REVERSAL_REQUEST,
                Permission.REVERSAL_RETRY,
                Permission.RISK_READ,
                Permission.RISK_REVIEW_ASSIGN,
                Permission.RISK_REVIEW_DECIDE,
                Permission.RISK_CONFIGURATION_MANAGE,
                Permission.PROVIDER_READ,
                Permission.PROVIDER_HEALTH_READ,
                Permission.SETTLEMENT_UPLOAD,
                Permission.RECONCILIATION_READ,
                Permission.RECONCILIATION_RUN,
                Permission.RECONCILIATION_PROMOTE,
                Permission.CASE_READ,
                Permission.CASE_ASSIGN,
                Permission.CASE_UPDATE,
                Permission.CASE_RESOLVE,
                Permission.CASE_CLOSE,
                Permission.CORRECTION_REQUEST,
                Permission.LEDGER_READ,
                Permission.AUDIT_READ,
                Permission.REPORT_READ,
                Permission.REPORT_EXPORT,
                Permission.NOTIFICATION_READ,
                Permission.WEBHOOK_ENDPOINT_MANAGE,
                Permission.WEBHOOK_TEST_TRIGGER,
                Permission.PAYMENT_CREATE));
    }

    @Test
    void enforcesEveryApprovedRoleScopeMode() {
        assertThat(TenantRole.TENANT_ADMIN.permits(ScopeMode.TENANT_WIDE)).isTrue();
        assertThat(TenantRole.TENANT_ADMIN.permits(ScopeMode.MERCHANT_SET)).isFalse();
        assertThat(TenantRole.RECONCILIATION_ANALYST.permits(ScopeMode.TENANT_WIDE)).isTrue();
        assertThat(TenantRole.RECONCILIATION_ANALYST.permits(ScopeMode.MERCHANT_SET)).isFalse();
        assertThat(TenantRole.MERCHANT_ADMIN.permits(ScopeMode.TENANT_WIDE)).isFalse();
        assertThat(TenantRole.MERCHANT_ADMIN.permits(ScopeMode.MERCHANT_SET)).isTrue();
        assertThat(TenantRole.INTEGRATION_DEVELOPER.permits(ScopeMode.TENANT_WIDE)).isFalse();
        assertThat(TenantRole.INTEGRATION_DEVELOPER.permits(ScopeMode.MERCHANT_SET)).isTrue();
        for (TenantRole flexible : Set.of(TenantRole.OPERATIONS_AGENT, TenantRole.RISK_ANALYST,
                TenantRole.AUDITOR, TenantRole.VIEWER)) {
            assertThat(flexible.permits(ScopeMode.TENANT_WIDE)).isTrue();
            assertThat(flexible.permits(ScopeMode.MERCHANT_SET)).isTrue();
            assertThat(flexible.permits(null)).isFalse();
        }
    }

    @Test
    void mapsEveryTenantRoleAndPlatformAdminExactly() {
        assertThat(TenantRole.TENANT_ADMIN.permissions()).isEqualTo(Set.of(
                Permission.TENANT_READ, Permission.TENANT_CONFIGURE,
                Permission.TENANT_MEMBERSHIP_MANAGE, Permission.TENANT_ROLE_MANAGE,
                Permission.MERCHANT_CREATE, Permission.MERCHANT_READ,
                Permission.MERCHANT_CONFIGURE, Permission.MERCHANT_SUSPEND,
                Permission.CREDENTIAL_MANAGE, Permission.PAYMENT_READ,
                Permission.RISK_READ, Permission.RISK_CONFIGURATION_MANAGE,
                Permission.PROVIDER_READ, Permission.PROVIDER_HEALTH_READ,
                Permission.RECONCILIATION_READ, Permission.CASE_READ,
                Permission.LEDGER_READ, Permission.AUDIT_READ,
                Permission.REPORT_READ, Permission.REPORT_EXPORT,
                Permission.NOTIFICATION_READ, Permission.WEBHOOK_ENDPOINT_MANAGE,
                Permission.WEBHOOK_TEST_TRIGGER));
        assertThat(TenantRole.MERCHANT_ADMIN.permissions()).isEqualTo(Set.of(
                Permission.TENANT_READ, Permission.TENANT_MEMBERSHIP_MANAGE,
                Permission.TENANT_ROLE_MANAGE, Permission.MERCHANT_READ,
                Permission.MERCHANT_CONFIGURE, Permission.CREDENTIAL_MANAGE,
                Permission.PAYMENT_READ, Permission.PAYMENT_NOTE_ADD,
                Permission.REVERSAL_REQUEST, Permission.RISK_READ,
                Permission.PROVIDER_READ, Permission.PROVIDER_HEALTH_READ,
                Permission.CASE_READ, Permission.LEDGER_READ,
                Permission.REPORT_READ, Permission.REPORT_EXPORT,
                Permission.NOTIFICATION_READ, Permission.WEBHOOK_ENDPOINT_MANAGE,
                Permission.WEBHOOK_TEST_TRIGGER));
        assertThat(TenantRole.OPERATIONS_AGENT.permissions()).isEqualTo(Set.of(
                Permission.TENANT_READ, Permission.MERCHANT_READ,
                Permission.PAYMENT_READ, Permission.PAYMENT_NOTE_ADD,
                Permission.PAYMENT_RETRY, Permission.REVERSAL_RETRY,
                Permission.PROVIDER_READ, Permission.PROVIDER_HEALTH_READ,
                Permission.CASE_READ, Permission.CASE_ASSIGN,
                Permission.CASE_UPDATE, Permission.LEDGER_READ,
                Permission.NOTIFICATION_READ));
        assertThat(TenantRole.RISK_ANALYST.permissions()).isEqualTo(Set.of(
                Permission.TENANT_READ, Permission.MERCHANT_READ,
                Permission.PAYMENT_READ, Permission.RISK_READ,
                Permission.RISK_REVIEW_ASSIGN, Permission.RISK_REVIEW_DECIDE,
                Permission.CASE_READ, Permission.CASE_ASSIGN,
                Permission.CASE_UPDATE, Permission.CASE_RESOLVE,
                Permission.CASE_CLOSE, Permission.NOTIFICATION_READ));
        assertThat(TenantRole.RECONCILIATION_ANALYST.permissions()).isEqualTo(Set.of(
                Permission.TENANT_READ, Permission.MERCHANT_READ,
                Permission.PAYMENT_READ, Permission.PROVIDER_READ,
                Permission.PROVIDER_HEALTH_READ, Permission.SETTLEMENT_UPLOAD,
                Permission.RECONCILIATION_READ, Permission.RECONCILIATION_RUN,
                Permission.RECONCILIATION_PROMOTE, Permission.CASE_READ,
                Permission.CASE_ASSIGN, Permission.CASE_UPDATE,
                Permission.CASE_RESOLVE, Permission.CASE_CLOSE,
                Permission.CORRECTION_REQUEST, Permission.LEDGER_READ,
                Permission.REPORT_READ, Permission.REPORT_EXPORT,
                Permission.NOTIFICATION_READ));
        assertThat(TenantRole.AUDITOR.permissions()).isEqualTo(Set.of(
                Permission.TENANT_READ, Permission.MERCHANT_READ,
                Permission.PAYMENT_READ, Permission.RISK_READ,
                Permission.PROVIDER_READ, Permission.PROVIDER_HEALTH_READ,
                Permission.RECONCILIATION_READ, Permission.CASE_READ,
                Permission.LEDGER_READ, Permission.AUDIT_READ,
                Permission.REPORT_READ, Permission.REPORT_EXPORT,
                Permission.NOTIFICATION_READ));
        assertThat(TenantRole.VIEWER.permissions()).isEqualTo(Set.of(
                Permission.TENANT_READ, Permission.MERCHANT_READ,
                Permission.PAYMENT_READ, Permission.RISK_READ,
                Permission.PROVIDER_READ, Permission.PROVIDER_HEALTH_READ,
                Permission.RECONCILIATION_READ, Permission.CASE_READ,
                Permission.LEDGER_READ, Permission.REPORT_READ,
                Permission.NOTIFICATION_READ));
        assertThat(TenantRole.INTEGRATION_DEVELOPER.permissions()).isEqualTo(Set.of(
                Permission.TENANT_READ, Permission.MERCHANT_READ,
                Permission.CREDENTIAL_MANAGE, Permission.PAYMENT_READ,
                Permission.PROVIDER_READ, Permission.WEBHOOK_ENDPOINT_MANAGE,
                Permission.WEBHOOK_TEST_TRIGGER, Permission.NOTIFICATION_READ));
        assertThat(PlatformRole.PLATFORM_ADMIN.permissions()).isEqualTo(Set.of(
                Permission.PLATFORM_TENANT_CREATE, Permission.PLATFORM_TENANT_ACTIVATE,
                Permission.PLATFORM_TENANT_SUSPEND, Permission.PLATFORM_TENANT_REACTIVATE,
                Permission.PLATFORM_TENANT_ARCHIVE, Permission.PLATFORM_PROVIDER_SCENARIO_MANAGE,
                Permission.PLATFORM_HEALTH_READ, Permission.PLATFORM_AUDIT_READ,
                Permission.SUPPORT_TENANT_READ));
        assertThat(PlatformRole.PLATFORM_ADMIN.permissions())
                .doesNotContainAnyElementsOf(TenantRole.TENANT_ADMIN.permissions());
        assertThat(Set.of(TenantRole.values()))
                .extracting(Enum::name)
                .doesNotContain(PlatformRole.PLATFORM_ADMIN.name());
    }

    @Test
    void addsRiskConfigurationOnlyToTenantWideRiskAssignments() {
        assertThat(TenantRole.RISK_ANALYST.permissions(ScopeMode.MERCHANT_SET))
                .isEqualTo(TenantRole.RISK_ANALYST.permissions());
        assertThat(TenantRole.RISK_ANALYST.permissions(ScopeMode.TENANT_WIDE))
                .isEqualTo(union(TenantRole.RISK_ANALYST.permissions(),
                        Set.of(Permission.RISK_CONFIGURATION_MANAGE)));
    }

    @Test
    void makesMerchantOwnershipValidationUnavoidable() {
        UUID tenant = UUID.randomUUID();
        UUID merchant = UUID.randomUUID();
        assertThatThrownBy(() -> MerchantScope.validated(tenant, Set.of(), Map.of()))
                .isInstanceOf(InvalidRoleAssignmentException.class);
        assertThatThrownBy(() -> MerchantScope.validated(
                tenant, Set.of(merchant), Map.of(merchant, UUID.randomUUID())))
                .isInstanceOf(InvalidRoleAssignmentException.class);
        assertThatThrownBy(() -> MerchantScope.validated(
                tenant, Set.of(merchant), Map.of()))
                .isInstanceOf(InvalidRoleAssignmentException.class);

        MerchantScope ownedScope = MerchantScope.validated(
                tenant, Set.of(merchant), Map.of(merchant, tenant));
        assertThatThrownBy(() -> TenantRoleAssignment.merchantScoped(
                TenantRoleAssignmentId.newId(), UUID.randomUUID(),
                TenantRole.VIEWER, ownedScope))
                .isInstanceOf(InvalidRoleAssignmentException.class);
        assertThatThrownBy(() -> TenantRoleAssignment.tenantWide(
                TenantRoleAssignmentId.newId(), tenant, TenantRole.MERCHANT_ADMIN))
                .isExactlyInstanceOf(InvalidRoleAssignmentException.class);
        assertThatThrownBy(() -> TenantRoleAssignment.merchantScoped(
                TenantRoleAssignmentId.newId(), tenant, TenantRole.TENANT_ADMIN, ownedScope))
                .isExactlyInstanceOf(InvalidRoleAssignmentException.class);
    }

    @Test
    void deniesPermissionAndMerchantSubsetEscalationIncludingSelfEscalation() {
        UUID tenant = UUID.randomUUID();
        UUID merchant = UUID.randomUUID();
        TenantRoleAssignment merchantAdmin = merchantAssignment(
                tenant, merchant, TenantRole.MERCHANT_ADMIN);
        TenantRoleAssignment operations = merchantAssignment(
                tenant, merchant, TenantRole.OPERATIONS_AGENT);

        assertThatThrownBy(() -> RoleGrantPolicy.validate(
                Set.of(merchantAdmin), operations))
                .isInstanceOf(GrantEscalationException.class);
        assertThatThrownBy(() -> RoleGrantPolicy.validate(
                Set.of(merchantAdmin), TenantRoleAssignment.tenantWide(
                        TenantRoleAssignmentId.newId(), tenant, TenantRole.TENANT_ADMIN)))
                .isInstanceOf(GrantEscalationException.class);

        UUID otherMerchant = UUID.randomUUID();
        assertThatThrownBy(() -> RoleGrantPolicy.validate(
                Set.of(merchantAdmin, operations),
                merchantAssignment(tenant, otherMerchant, TenantRole.OPERATIONS_AGENT)))
                .isInstanceOf(GrantEscalationException.class);

        TenantRoleAssignment operationsElsewhere = merchantAssignment(
                tenant, otherMerchant, TenantRole.OPERATIONS_AGENT);
        assertThatThrownBy(() -> RoleGrantPolicy.validate(
                Set.of(merchantAdmin, operationsElsewhere), operationsElsewhere))
                .isInstanceOf(GrantEscalationException.class);

        TenantRoleAssignment widerMerchantAdmin = merchantAssignment(
                tenant, Set.of(merchant, otherMerchant), TenantRole.MERCHANT_ADMIN);
        assertThatThrownBy(() -> RoleGrantPolicy.validate(
                Set.of(merchantAdmin, operations, operationsElsewhere), widerMerchantAdmin))
                .isExactlyInstanceOf(GrantEscalationException.class);

        TenantRoleAssignment tenantAdmin = TenantRoleAssignment.tenantWide(
                TenantRoleAssignmentId.newId(), tenant, TenantRole.TENANT_ADMIN);
        RoleGrantPolicy.validate(Set.of(tenantAdmin), TenantRoleAssignment.tenantWide(
                TenantRoleAssignmentId.newId(), tenant, TenantRole.OPERATIONS_AGENT));
        RoleGrantPolicy.validate(Set.of(tenantAdmin), merchantAssignment(
                tenant, merchant, TenantRole.OPERATIONS_AGENT));
    }

    @Test
    void permitsGrantOnlyWhenCompleteScopedAuthorityContainsIt() {
        UUID tenant = UUID.randomUUID();
        UUID merchant = UUID.randomUUID();
        TenantRoleAssignment merchantAdmin = merchantAssignment(
                tenant, merchant, TenantRole.MERCHANT_ADMIN);
        TenantRoleAssignment operations = merchantAssignment(
                tenant, merchant, TenantRole.OPERATIONS_AGENT);

        RoleGrantPolicy.validate(Set.of(merchantAdmin, operations),
                merchantAssignment(tenant, merchant, TenantRole.OPERATIONS_AGENT));
    }

    @Test
    void rejectsMixedTenantActorFacts() {
        UUID tenant = UUID.randomUUID();
        TenantRoleAssignment requested = TenantRoleAssignment.tenantWide(
                TenantRoleAssignmentId.newId(), tenant, TenantRole.VIEWER);
        assertThatThrownBy(() -> RoleGrantPolicy.validate(Set.of(
                TenantRoleAssignment.tenantWide(
                        TenantRoleAssignmentId.newId(), tenant, TenantRole.TENANT_ADMIN),
                TenantRoleAssignment.tenantWide(
                        TenantRoleAssignmentId.newId(), UUID.randomUUID(),
                        TenantRole.VIEWER)), requested))
                .isInstanceOf(GrantEscalationException.class);
    }

    private TenantRoleAssignment merchantAssignment(UUID tenant, UUID merchant, TenantRole role) {
        return merchantAssignment(tenant, Set.of(merchant), role);
    }

    private TenantRoleAssignment merchantAssignment(
            UUID tenant,
            Set<UUID> merchants,
            TenantRole role
    ) {
        Map<UUID, UUID> ownership = merchants.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        merchant -> merchant,
                        merchant -> tenant));
        return TenantRoleAssignment.merchantScoped(TenantRoleAssignmentId.newId(), tenant, role,
                MerchantScope.validated(tenant, merchants, ownership));
    }

    private Set<Permission> union(Set<Permission> first, Set<Permission> second) {
        java.util.HashSet<Permission> result = new java.util.HashSet<>(first);
        result.addAll(second);
        return Set.copyOf(result);
    }
}
