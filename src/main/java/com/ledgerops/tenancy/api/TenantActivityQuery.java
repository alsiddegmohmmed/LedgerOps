package com.ledgerops.tenancy.api;

public interface TenantActivityQuery {

    TenantActivityStatus evaluate(TenantReference tenantReference);

    TenantActivityStatus evaluateForUpdate(TenantReference tenantReference);
}
