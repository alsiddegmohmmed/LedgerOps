package com.ledgerops.identity.api;

public interface PlatformAuthorityPort {

    void requirePlatformAdmin(AuthenticatedPrincipal principal);
}
