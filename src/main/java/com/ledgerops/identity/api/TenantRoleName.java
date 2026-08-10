package com.ledgerops.identity.api;

/**
 * Public request-contract names for the closed Release 0.3 Tenant role set.
 * The Identity domain owns the internal role model and maps these names to it.
 */
public enum TenantRoleName {
    TENANT_ADMIN,
    MERCHANT_ADMIN,
    OPERATIONS_AGENT,
    RISK_ANALYST,
    RECONCILIATION_ANALYST,
    AUDITOR,
    VIEWER,
    INTEGRATION_DEVELOPER
}
