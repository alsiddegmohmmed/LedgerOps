package com.ledgerops.provider.application;

import java.util.Optional;
import java.util.UUID;

public interface ProviderExecutionStore {
    Optional<ProviderWorkClaim> claimNext(String leaseOwner);
    Optional<ProviderRetryRequestClaim> claimRetryRequest(String leaseOwner);
    void issueRetryRequest(ProviderRetryRequestClaim claim);
    boolean renew(UUID workId, UUID leaseToken);
    void defer(ProviderWorkClaim claim, String reasonCode);
    void markUnresolved(ProviderWorkClaim claim, String reasonCode);
    void markAmbiguous(ProviderWorkClaim claim, String reasonCode);
    void record(ProviderWorkClaim claim, ProviderCallResult result);
}
