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
                    Permission.CREDENTIAL_MANAGE, Permission.REPORT_READ,
                    Permission.REPORT_EXPORT
            )
    ),
    MERCHANT_ADMIN(
            ScopeMode.MERCHANT_SET,
            Set.of(
                    Permission.MERCHANT_READ, Permission.MERCHANT_CONFIGURE,
                    Permission.CREDENTIAL_MANAGE, Permission.REPORT_READ,
                    Permission.REPORT_EXPORT, Permission.REVERSAL_REQUEST
            )
    ),
    OPERATIONS_AGENT(
            null,
            Set.of(Permission.PAYMENT_READ, Permission.PAYMENT_NOTE_ADD,
                    Permission.PAYMENT_RETRY, Permission.CASE_READ,
                    Permission.CASE_ASSIGN, Permission.CASE_UPDATE)
    ),
    RISK_ANALYST(
            null,
            Set.of(Permission.RISK_READ, Permission.RISK_REVIEW_ASSIGN,
                    Permission.RISK_REVIEW_DECIDE, Permission.RISK_CONFIGURATION_MANAGE)
    ),
    RECONCILIATION_ANALYST(
            ScopeMode.TENANT_WIDE,
            Set.of(Permission.SETTLEMENT_UPLOAD, Permission.RECONCILIATION_READ,
                    Permission.RECONCILIATION_RUN, Permission.RECONCILIATION_PROMOTE,
                    Permission.CASE_READ, Permission.CASE_ASSIGN, Permission.CASE_UPDATE,
                    Permission.CASE_RESOLVE, Permission.CASE_CLOSE,
                    Permission.CORRECTION_REQUEST)
    ),
    AUDITOR(
            null,
            Set.of(Permission.PAYMENT_READ, Permission.LEDGER_READ, Permission.AUDIT_READ,
                    Permission.RECONCILIATION_READ, Permission.REPORT_READ,
                    Permission.REPORT_EXPORT)
    ),
    VIEWER(
            null,
            Set.of(Permission.PAYMENT_READ, Permission.RISK_READ,
                    Permission.PROVIDER_READ, Permission.NOTIFICATION_READ,
                    Permission.REPORT_READ)
    ),
    INTEGRATION_DEVELOPER(
            ScopeMode.MERCHANT_SET,
            Set.of(Permission.CREDENTIAL_MANAGE, Permission.WEBHOOK_ENDPOINT_MANAGE,
                    Permission.WEBHOOK_TEST_TRIGGER)
    );

    private final ScopeMode requiredScopeMode;
    private final Set<Permission> permissions;

    TenantRole(ScopeMode requiredScopeMode, Set<Permission> permissions) {
        this.requiredScopeMode = requiredScopeMode;
        this.permissions = Set.copyOf(permissions);
    }

    public void validateScope(ScopeMode scopeMode) {
        if (requiredScopeMode != null && requiredScopeMode != scopeMode) {
            throw new IllegalArgumentException(name() + " requires " + requiredScopeMode);
        }
    }

    public Set<Permission> permissions() {
        return permissions;
    }
}
