package com.ledgerops.identity.application;

import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.PlatformAuthorityPort;
import com.ledgerops.identity.api.PlatformAuthorizationException;
import com.ledgerops.identity.domain.PlatformAuthorityRepository;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
class PlatformAuthorityService implements PlatformAuthorityPort {

    private final PlatformAuthorityRepository authorities;

    PlatformAuthorityService(
            PlatformAuthorityRepository authorities
    ) {
        this.authorities = Objects.requireNonNull(
                authorities, "Platform authority repository must not be null");
    }

    @Override
    public void requirePlatformAdmin(AuthenticatedPrincipal principal) {
        Objects.requireNonNull(principal, "Authenticated principal must not be null");
        if (!"HUMAN".equals(principal.principalType())
                || !authorities.hasPlatformAdmin(principal.issuer(), principal.subject())) {
            throw new PlatformAuthorizationException();
        }
    }
}
