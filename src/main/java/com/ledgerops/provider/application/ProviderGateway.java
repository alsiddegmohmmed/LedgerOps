package com.ledgerops.provider.application;

public interface ProviderGateway {
    ProviderCallResult execute(ProviderWorkClaim claim);
}
