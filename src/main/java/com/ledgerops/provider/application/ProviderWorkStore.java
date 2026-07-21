package com.ledgerops.provider.application;

public interface ProviderWorkStore {
    void createOrVerifySubmission(ProviderSubmissionCommand command);
}
