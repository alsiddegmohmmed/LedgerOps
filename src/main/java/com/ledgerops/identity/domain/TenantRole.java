package com.ledgerops.identity.domain;

import java.util.Set;

public enum TenantRole {
    TENANT_ADMIN(
            ScopeMode.TENANT_WIDE,
            Set.of(
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
                    Permission.WEBHOOK_TEST_TRIGGER
            )
    ),
    MERCHANT_ADMIN(
            ScopeMode.MERCHANT_SET,
            Set.of(
                    Permission.TENANT_READ, Permission.TENANT_MEMBERSHIP_MANAGE,
                    Permission.TENANT_ROLE_MANAGE, Permission.MERCHANT_READ,
                    Permission.MERCHANT_CONFIGURE,
                    Permission.CREDENTIAL_MANAGE, Permission.REPORT_READ,
                    Permission.REPORT_EXPORT, Permission.PAYMENT_READ,
                    Permission.PAYMENT_NOTE_ADD, Permission.REVERSAL_REQUEST,
                    Permission.RISK_READ, Permission.PROVIDER_READ,
                    Permission.PROVIDER_HEALTH_READ, Permission.CASE_READ,
                    Permission.LEDGER_READ, Permission.NOTIFICATION_READ,
                    Permission.WEBHOOK_ENDPOINT_MANAGE, Permission.WEBHOOK_TEST_TRIGGER
            )
    ),
    OPERATIONS_AGENT(
            null,
            Set.of(Permission.TENANT_READ, Permission.MERCHANT_READ,
                    Permission.PAYMENT_READ, Permission.PAYMENT_NOTE_ADD,
                    Permission.PAYMENT_RETRY, Permission.REVERSAL_RETRY,
                    Permission.PROVIDER_READ, Permission.PROVIDER_HEALTH_READ,
                    Permission.CASE_READ, Permission.CASE_ASSIGN,
                    Permission.CASE_UPDATE, Permission.LEDGER_READ,
                    Permission.NOTIFICATION_READ)
    ),
    RISK_ANALYST(
            null,
            Set.of(Permission.TENANT_READ, Permission.MERCHANT_READ,
                    Permission.PAYMENT_READ, Permission.RISK_READ,
                    Permission.RISK_REVIEW_ASSIGN, Permission.RISK_REVIEW_DECIDE,
                    Permission.CASE_READ, Permission.CASE_ASSIGN,
                    Permission.CASE_UPDATE, Permission.CASE_RESOLVE,
                    Permission.CASE_CLOSE, Permission.NOTIFICATION_READ)
    ),
    RECONCILIATION_ANALYST(
            ScopeMode.TENANT_WIDE,
            Set.of(Permission.TENANT_READ, Permission.MERCHANT_READ,
                    Permission.PAYMENT_READ, Permission.PROVIDER_READ,
                    Permission.PROVIDER_HEALTH_READ, Permission.SETTLEMENT_UPLOAD,
                    Permission.RECONCILIATION_READ,
                    Permission.RECONCILIATION_RUN, Permission.RECONCILIATION_PROMOTE,
                    Permission.CASE_READ, Permission.CASE_ASSIGN, Permission.CASE_UPDATE,
                    Permission.CASE_RESOLVE, Permission.CASE_CLOSE,
                    Permission.CORRECTION_REQUEST, Permission.LEDGER_READ,
                    Permission.REPORT_READ, Permission.REPORT_EXPORT,
                    Permission.NOTIFICATION_READ)
    ),
    AUDITOR(
            null,
            Set.of(Permission.TENANT_READ, Permission.MERCHANT_READ,
                    Permission.PAYMENT_READ, Permission.RISK_READ,
                    Permission.PROVIDER_READ, Permission.PROVIDER_HEALTH_READ,
                    Permission.RECONCILIATION_READ, Permission.CASE_READ,
                    Permission.LEDGER_READ, Permission.AUDIT_READ,
                    Permission.REPORT_READ, Permission.REPORT_EXPORT,
                    Permission.NOTIFICATION_READ)
    ),
    VIEWER(
            null,
            Set.of(Permission.TENANT_READ, Permission.MERCHANT_READ,
                    Permission.PAYMENT_READ, Permission.RISK_READ,
                    Permission.PROVIDER_READ, Permission.PROVIDER_HEALTH_READ,
                    Permission.RECONCILIATION_READ, Permission.CASE_READ,
                    Permission.LEDGER_READ, Permission.REPORT_READ,
                    Permission.NOTIFICATION_READ)
    ),
    INTEGRATION_DEVELOPER(
            ScopeMode.MERCHANT_SET,
            Set.of(Permission.TENANT_READ, Permission.MERCHANT_READ,
                    Permission.CREDENTIAL_MANAGE, Permission.PAYMENT_READ,
                    Permission.PROVIDER_READ, Permission.WEBHOOK_ENDPOINT_MANAGE,
                    Permission.WEBHOOK_TEST_TRIGGER, Permission.NOTIFICATION_READ)
    );

    private final ScopeMode requiredScopeMode;
    private final Set<Permission> permissions;

    TenantRole(ScopeMode requiredScopeMode, Set<Permission> permissions) {
        this.requiredScopeMode = requiredScopeMode;
        this.permissions = Set.copyOf(permissions);
    }

    public void validateScope(ScopeMode scopeMode) {
        if (scopeMode == null || (requiredScopeMode != null && requiredScopeMode != scopeMode)) {
            throw new IllegalArgumentException(name() + " requires " + requiredScopeMode);
        }
    }

    public boolean permits(ScopeMode scopeMode) {
        return scopeMode != null
                && (requiredScopeMode == null || requiredScopeMode == scopeMode);
    }

    public ScopeMode requiredScopeMode() {
        return requiredScopeMode;
    }

    public Set<Permission> permissions() {
        return permissions;
    }

    /**
     * Returns the effective permissions for an assignment scope.  Risk
     * configuration is deliberately available only to a Tenant-wide analyst.
     */
    public Set<Permission> permissions(ScopeMode scopeMode) {
        validateScope(scopeMode);
        if (this == RISK_ANALYST && scopeMode == ScopeMode.TENANT_WIDE) {
            var effective = new java.util.HashSet<>(permissions);
            effective.add(Permission.RISK_CONFIGURATION_MANAGE);
            return Set.copyOf(effective);
        }
        return permissions;
    }
}
