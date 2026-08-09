package com.ledgerops.identity.api;

import java.util.UUID;

/**
 * Identity's application boundary for local-first sandbox credential
 * revocation. Repeating the same operation retries external cleanup.
 */
public interface ServiceCredentialRevocationPort {

    ServiceCredentialRevocationResult revoke(UUID credentialId);
}
