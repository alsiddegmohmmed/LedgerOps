package com.ledgerops.identity.application;

import com.ledgerops.identity.domain.ApplicationUserId;
import com.ledgerops.identity.domain.PrincipalType;

import java.util.Optional;
import java.util.UUID;

public interface AuthorizedTenantContextPort {

    Optional<AuthorizedTenantContext> find(
            ApplicationUserId applicationUserId,
            PrincipalType principalType,
            String serviceClientId,
            UUID tenantId
    );
}
