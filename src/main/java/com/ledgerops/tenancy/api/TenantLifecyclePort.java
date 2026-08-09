package com.ledgerops.tenancy.api;

public interface TenantLifecyclePort {

    TenantReference suspend(TenantLifecycleRequest request);

    TenantReference archive(TenantLifecycleRequest request);
}
