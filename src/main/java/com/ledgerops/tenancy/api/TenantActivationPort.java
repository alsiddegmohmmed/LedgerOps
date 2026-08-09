package com.ledgerops.tenancy.api;

public interface TenantActivationPort {

    /**
     * Acquires the Tenant row lock for the surrounding transaction before
     * other modules calculate activation readiness.
     */
    void lockForActivation(TenantReference tenant);

    TenantReference activate(TenantActivationRequest request);
}
