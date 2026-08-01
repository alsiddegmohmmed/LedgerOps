package com.ledgerops.identity.domain;

public final class LastActiveTenantAdminException extends IllegalStateException {

    public LastActiveTenantAdminException() {
        super("An active Tenant must retain at least one active Tenant Admin");
    }
}
