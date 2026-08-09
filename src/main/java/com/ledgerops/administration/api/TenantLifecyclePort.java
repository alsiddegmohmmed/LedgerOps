package com.ledgerops.administration.api;

public interface TenantLifecyclePort {

    TenantLifecycleResult suspend(TenantLifecycleCommand command);

    TenantLifecycleResult archive(TenantLifecycleCommand command);
}
