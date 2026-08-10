package com.ledgerops.identity.api;

import java.util.Optional;
import java.util.UUID;

public interface SupportSessionPort {

    SupportSessionResult start(SupportSessionStartCommand command);

    Optional<AuthorizedRequestContext> authorize(
            UUID supportSessionId,
            AuthenticatedPrincipal actor,
            UUID tenantId,
            String correlationId,
            String resourcePath
    );
}
