package com.ledgerops.administration.api;

public interface TenantActivationPort {

    TenantActivationResult activate(TenantActivationCommand command);
}
