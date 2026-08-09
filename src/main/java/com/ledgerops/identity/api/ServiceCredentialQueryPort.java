package com.ledgerops.identity.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Identity's non-secret credential metadata query for owning application
 * workflows. It never returns a client secret.
 */
public interface ServiceCredentialQueryPort {

    Optional<ServiceCredentialMetadata> find(UUID credentialId);
}
