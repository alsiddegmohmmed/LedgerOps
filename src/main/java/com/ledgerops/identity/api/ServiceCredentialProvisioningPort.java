package com.ledgerops.identity.api;

import java.util.UUID;

/**
 * Identity's application boundary for sandbox credential provisioning.
 *
 * <p>The returned client secret is one-time response data. It is not Core
 * state and must never be persisted, logged, or returned by a later read.</p>
 */
public interface ServiceCredentialProvisioningPort {

    ServiceCredentialProvisioningResult provision(ServiceCredentialProvisioningRequest request);

    ServiceCredentialProvisioningResult retry(UUID operationId);

    /**
     * Provisions a replacement for the active credential and returns its
     * one-time secret after local rotation has committed.
     */
    ServiceCredentialProvisioningResult rotate(UUID credentialId);

    /**
     * Retries disabling the old external client after a completed rotation.
     */
    void retryRotationCleanup(UUID replacementCredentialId);
}
