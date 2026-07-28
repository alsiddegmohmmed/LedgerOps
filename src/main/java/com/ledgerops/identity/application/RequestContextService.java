package com.ledgerops.identity.application;

import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.identity.domain.ApplicationUser;
import com.ledgerops.identity.domain.ApplicationUserRepository;
import com.ledgerops.identity.domain.ApplicationUserStatus;
import com.ledgerops.identity.domain.PrincipalType;

import java.util.Objects;
import java.util.UUID;

public final class RequestContextService {

    private final ApplicationUserRepository applicationUserRepository;
    private final AuthorizedTenantContextPort authorizedTenantContextPort;

    public RequestContextService(
            ApplicationUserRepository applicationUserRepository,
            AuthorizedTenantContextPort authorizedTenantContextPort
    ) {
        this.applicationUserRepository = Objects.requireNonNull(
                applicationUserRepository,
                "Application user repository must not be null"
        );
        this.authorizedTenantContextPort = Objects.requireNonNull(
                authorizedTenantContextPort,
                "Authorized Tenant context port must not be null"
        );
    }

    public AuthorizedRequestContext create(
            ValidatedPrincipal principal,
            UUID selectedTenantId,
            String correlationId
    ) {
        Objects.requireNonNull(principal, "Validated principal must not be null");
        if (selectedTenantId == null) {
            throw new InvalidTenantSelectionException();
        }

        ApplicationUser user = applicationUserRepository
                .findByKeycloakIdentity(principal.keycloakIdentity())
                .orElseThrow(UnknownApplicationIdentityException::new);

        if (user.status() == ApplicationUserStatus.DEACTIVATED) {
            throw new InactiveApplicationUserException();
        }

        AuthorizedTenantContext tenantContext = authorizedTenantContextPort.find(
                        user.id(),
                        principal.principalType(),
                        principal.serviceClientId(),
                        selectedTenantId
                )
                .orElseThrow(InvalidTenantSelectionException::new);

        UUID serviceCredentialId = principal.principalType() == PrincipalType.SERVICE
                ? tenantContext.serviceCredentialId()
                : null;
        return new AuthorizedRequestContext(
                principal.principalType(),
                user.id().value(),
                serviceCredentialId,
                tenantContext.tenantId(),
                tenantContext.scopeMode(),
                tenantContext.merchantIds(),
                tenantContext.permissions(),
                correlationId
        );
    }
}
