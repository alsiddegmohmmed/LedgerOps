package com.ledgerops.identity.domain;

public enum Permission {
    PLATFORM_AUDIT_READ("platform:audit-read"),
    TENANT_READ("tenant:read"),
    TENANT_CONFIGURE("tenant:configure"),
    TENANT_MEMBERSHIP_MANAGE("tenant:membership-manage"),
    TENANT_ROLE_MANAGE("tenant:role-manage"),
    MERCHANT_CREATE("merchant:create"),
    MERCHANT_READ("merchant:read"),
    MERCHANT_CONFIGURE("merchant:configure"),
    MERCHANT_SUSPEND("merchant:suspend"),
    CREDENTIAL_MANAGE("credential:manage"),
    PAYMENT_READ("payment:read"),
    PAYMENT_NOTE_ADD("payment:note-add"),
    PAYMENT_RETRY("payment:retry"),
    REVERSAL_REQUEST("reversal:request"),
    REVERSAL_RETRY("reversal:retry"),
    RISK_READ("risk:read"),
    RISK_REVIEW_ASSIGN("risk:review-assign"),
    RISK_REVIEW_DECIDE("risk:review-decide"),
    RISK_CONFIGURATION_MANAGE("risk:configuration-manage"),
    PROVIDER_READ("provider:read"),
    PROVIDER_HEALTH_READ("provider:health-read"),
    SETTLEMENT_UPLOAD("settlement:upload"),
    RECONCILIATION_READ("reconciliation:read"),
    RECONCILIATION_RUN("reconciliation:run"),
    RECONCILIATION_PROMOTE("reconciliation:promote"),
    CASE_READ("case:read"),
    CASE_ASSIGN("case:assign"),
    CASE_UPDATE("case:update"),
    CASE_RESOLVE("case:resolve"),
    CASE_CLOSE("case:close"),
    CORRECTION_REQUEST("correction:request"),
    LEDGER_READ("ledger:read"),
    AUDIT_READ("audit:read"),
    REPORT_READ("report:read"),
    REPORT_EXPORT("report:export"),
    NOTIFICATION_READ("notification:read"),
    WEBHOOK_ENDPOINT_MANAGE("webhook:endpoint-manage"),
    WEBHOOK_TEST_TRIGGER("webhook:test-trigger"),
    SUPPORT_TENANT_READ("support:tenant-read"),
    PAYMENT_CREATE("payment:create");

    private final String value;

    Permission(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
