package com.ledgerops.identity.application;

import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.identity.domain.ApplicationUser;
import com.ledgerops.identity.domain.ApplicationUserId;
import com.ledgerops.identity.domain.ApplicationUserRepository;
import com.ledgerops.identity.domain.KeycloakIdentity;
import com.ledgerops.identity.domain.Permission;
import com.ledgerops.identity.domain.PrincipalType;
import com.ledgerops.identity.domain.ScopeMode;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestContextServiceTests {

    @Test
    void buildsContextFromApplicationDataForAnExplicitTenantSelection() {
        ApplicationUser user = user(PrincipalType.HUMAN);
        UUID tenantId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        AuthorizedTenantContext authorization = new AuthorizedTenantContext(
                tenantId,
                ScopeMode.MERCHANT_SET,
                Set.of(merchantId),
                Set.of(Permission.PAYMENT_READ),
                null
        );
        RequestContextService service = service(user, authorization);

        AuthorizedRequestContext context = service.create(
                new ValidatedPrincipal(PrincipalType.HUMAN, user.keycloakIdentity(), null),
                tenantId,
                "correlation-1"
        );

        assertThat(context.principalType()).isEqualTo(PrincipalType.HUMAN);
        assertThat(context.applicationUserId()).isEqualTo(user.id().value());
        assertThat(context.tenantId()).isEqualTo(tenantId);
        assertThat(context.merchantIds()).containsExactly(merchantId);
        assertThat(context.permissions()).containsExactly(Permission.PAYMENT_READ);
    }

    @Test
    void preservesServicePrincipalTypeButUsesApplicationAuthorizationData() {
        ApplicationUser user = user(PrincipalType.SERVICE);
        UUID tenantId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        AuthorizedTenantContext authorization = new AuthorizedTenantContext(
                tenantId,
                ScopeMode.TENANT_WIDE,
                Set.of(),
                Set.of(Permission.PAYMENT_CREATE),
                credentialId
        );

        AuthorizedRequestContext context = service(user, authorization).create(
                new ValidatedPrincipal(PrincipalType.SERVICE, user.keycloakIdentity(), "client-id"),
                tenantId,
                "correlation-2"
        );

        assertThat(context.principalType()).isEqualTo(PrincipalType.SERVICE);
        assertThat(context.serviceCredentialId()).isEqualTo(credentialId);
        assertThat(context.permissions()).containsExactly(Permission.PAYMENT_CREATE);
    }

    @Test
    void rejectsUnknownAndDeactivatedUsers() {
        ApplicationUser unknown = user(PrincipalType.HUMAN);
        RequestContextService unknownService = new RequestContextService(
                repositoryFor(null),
                (id, type, client, tenant) -> Optional.empty()
        );

        assertThatThrownBy(() -> unknownService.create(
                new ValidatedPrincipal(PrincipalType.HUMAN, unknown.keycloakIdentity(), null),
                UUID.randomUUID(),
                "correlation-3"
        )).isInstanceOf(UnknownApplicationIdentityException.class);

        ApplicationUser deactivated = unknown.deactivate();
        RequestContextService inactiveService = service(deactivated, null);
        assertThatThrownBy(() -> inactiveService.create(
                new ValidatedPrincipal(PrincipalType.HUMAN, deactivated.keycloakIdentity(), null),
                UUID.randomUUID(),
                "correlation-4"
        )).isInstanceOf(InactiveApplicationUserException.class);
    }

    @Test
    void rejectsMissingOrUnauthorizedTenantWithoutUsingClientAuthority() {
        ApplicationUser user = user(PrincipalType.HUMAN);
        RequestContextService service = service(user, null);

        assertThatThrownBy(() -> service.create(
                new ValidatedPrincipal(PrincipalType.HUMAN, user.keycloakIdentity(), null),
                UUID.randomUUID(),
                "correlation-5"
        )).isInstanceOf(InvalidTenantSelectionException.class);

        assertThatThrownBy(() -> service.create(
                new ValidatedPrincipal(PrincipalType.HUMAN, user.keycloakIdentity(), null),
                null,
                "correlation-6"
        )).isInstanceOf(InvalidTenantSelectionException.class);
    }

    private RequestContextService service(
            ApplicationUser user,
            AuthorizedTenantContext authorization
    ) {
        return new RequestContextService(
                repositoryFor(user),
                (id, type, client, tenant) -> authorization != null
                        && id.equals(user.id())
                        && tenant.equals(authorization.tenantId())
                        ? Optional.of(authorization)
                        : Optional.empty()
        );
    }

    private ApplicationUserRepository repositoryFor(ApplicationUser user) {
        return new ApplicationUserRepository() {
            @Override
            public ApplicationUser save(ApplicationUser applicationUser) {
                return applicationUser;
            }

            @Override
            public Optional<ApplicationUser> findById(ApplicationUserId id) {
                return user != null && user.id().equals(id)
                        ? Optional.of(user)
                        : Optional.empty();
            }

            @Override
            public Optional<ApplicationUser> findByKeycloakIdentity(KeycloakIdentity identity) {
                return user != null && user.keycloakIdentity().equals(identity)
                        ? Optional.of(user)
                        : Optional.empty();
            }
        };
    }

    private ApplicationUser user(PrincipalType principalType) {
        return ApplicationUser.create(
                ApplicationUserId.newId(),
                new KeycloakIdentity(
                        "issuer",
                        principalType.name().toLowerCase() + "-subject"
                )
        );
    }
}
