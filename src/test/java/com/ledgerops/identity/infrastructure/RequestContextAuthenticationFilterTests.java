package com.ledgerops.identity.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerops.RequestCorrelationFilter;
import com.ledgerops.identity.application.AuthorizedTenantContext;
import com.ledgerops.identity.application.RequestContextService;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.SupportSessionPort;
import com.ledgerops.identity.api.SupportSessionResult;
import com.ledgerops.identity.api.SupportSessionStartCommand;
import com.ledgerops.identity.domain.ApplicationUser;
import com.ledgerops.identity.domain.ApplicationUserId;
import com.ledgerops.identity.domain.ApplicationUserRepository;
import com.ledgerops.identity.domain.KeycloakIdentity;
import com.ledgerops.identity.domain.Permission;
import com.ledgerops.identity.domain.PrincipalType;
import com.ledgerops.identity.domain.ScopeMode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.slf4j.MDC;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class RequestContextAuthenticationFilterTests {

    private static final Instant NOW = Instant.parse("2026-07-28T10:00:00Z");
    private static final String ISSUER = "https://keycloak.example/realms/ledgerops";
    private static final String AUDIENCE = "ledgerops-core";

    @Test
    void rejectsMissingMalformedAndInvalidBearerTokensWith401() throws Exception {
        assertUnauthorized(null, "AUTHENTICATION_REQUIRED");
        assertUnauthorized("Basic abc", "AUTHENTICATION_REQUIRED");
        assertUnauthorized("Bearer invalid", "INVALID_AUTHENTICATION");
    }

    @Test
    void attachesPostgresDerivedRequestContextForValidBearerToken() throws Exception {
        UUID tenantId = UUID.randomUUID();
        ApplicationUser user = ApplicationUser.create(ApplicationUserId.newId(),
                new KeycloakIdentity(ISSUER, "subject-1"));
        RequestContextAuthenticationFilter filter = filter(user, tenantId);
        MockHttpServletRequest request = tenantRequest(tenantId);
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer valid");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();

        try (MDC.MDCCloseable ignored = MDC.putCloseable("correlationId", "correlation-1")) {
            filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> continued.set(true));
        }

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(continued).isTrue();
        assertThat(request.getAttribute(RequestContextAuthenticationFilter.CONTEXT_ATTRIBUTE))
                .isInstanceOf(com.ledgerops.identity.api.AuthorizedRequestContext.class);
    }

    @Test
    void rejectsMissingPaymentAuthenticationWith401() throws Exception {
        UUID tenantId = UUID.randomUUID();
        RequestContextAuthenticationFilter filter = filter(null, tenantId);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/payments");
        request.addHeader(RequestContextAuthenticationFilter.TENANT_SELECTION_HEADER, tenantId.toString());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> { });

        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(response.getContentAsString()).contains("AUTHENTICATION_REQUIRED");
    }

    @Test
    void attachesRequestContextForCredentialMetadataAndActionRoutes() throws Exception {
        UUID tenantId = UUID.randomUUID();
        RequestContextAuthenticationFilter filter = filter(
                ApplicationUser.create(ApplicationUserId.newId(),
                        new KeycloakIdentity(ISSUER, "subject-1")),
                tenantId
        );

        for (String methodAndPath : new String[] {
                "GET /api/v1/tenants/" + tenantId + "/credentials",
                "GET /api/v1/tenants/" + tenantId + "/credentials/"
                        + UUID.randomUUID(),
                "POST /api/v1/tenants/" + tenantId + "/credentials",
                "POST /api/v1/tenants/" + tenantId + "/credentials/"
                        + UUID.randomUUID() + "/rotate",
                "POST /api/v1/tenants/" + tenantId + "/credentials/"
                        + UUID.randomUUID() + "/revoke",
                "GET /api/v1/tenants/" + tenantId + "/configuration",
                "PUT /api/v1/tenants/" + tenantId + "/configuration",
                "GET /api/v1/tenants/" + tenantId + "/operational-contacts",
                "PUT /api/v1/tenants/" + tenantId + "/operational-contacts/"
                        + UUID.randomUUID(),
                "GET /api/v1/tenants/" + tenantId + "/merchants",
                "GET /api/v1/tenants/" + tenantId + "/merchants/"
                        + UUID.randomUUID(),
                "GET /api/v1/tenants/" + tenantId + "/memberships",
                "GET /api/v1/tenants/" + tenantId + "/memberships/"
                        + UUID.randomUUID(),
                "POST /api/v1/tenants/" + tenantId + "/memberships/"
                        + UUID.randomUUID() + "/invitation/revoke"
        }) {
            String[] parts = methodAndPath.split(" ", 2);
            MockHttpServletRequest request = new MockHttpServletRequest(parts[0], parts[1]);
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer valid");
            request.setAttribute(RequestCorrelationFilter.CORRELATION_ID,
                    "credential-correlation");
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean continued = new AtomicBoolean();

            filter.doFilter(request, response,
                    (ignoredRequest, ignoredResponse) -> continued.set(true));

            assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
            assertThat(continued).isTrue();
            assertThat(request.getAttribute(RequestContextAuthenticationFilter.CONTEXT_ATTRIBUTE))
                    .isInstanceOf(com.ledgerops.identity.api.AuthorizedRequestContext.class);
        }
    }

    @Test
    void attachesPrincipalButDoesNotCreateTenantContextForPlatformActivation() throws Exception {
        UUID tenantId = UUID.randomUUID();
        RequestContextAuthenticationFilter filter = filter(
                ApplicationUser.create(ApplicationUserId.newId(),
                        new KeycloakIdentity(ISSUER, "subject-1")),
                tenantId
        );
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/v1/tenants/" + tenantId + "/activate");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer valid");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilter(request, response,
                (ignoredRequest, ignoredResponse) -> continued.set(true));

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(continued).isTrue();
        assertThat(request.getAttribute(
                com.ledgerops.identity.api.AuthorizedRequestContextRequest.principalAttribute()))
                .isEqualTo(new com.ledgerops.identity.api.AuthenticatedPrincipal(
                        "HUMAN", ISSUER, "subject-1"));
        assertThat(request.getAttribute(RequestContextAuthenticationFilter.CONTEXT_ATTRIBUTE))
                .isNull();
    }

    @Test
    void createsReadOnlyContextForValidSupportSessionAndRejectsWrites() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID supportSessionId = UUID.randomUUID();
        SupportSessionPort supportSessions = new SupportSessionPort() {
            @Override
            public SupportSessionResult start(SupportSessionStartCommand command) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<AuthorizedRequestContext> authorize(
                    UUID requestedSessionId,
                    AuthenticatedPrincipal actor,
                    UUID requestedTenantId,
                    String correlationId,
                    String resourcePath
            ) {
                return supportSessionId.equals(requestedSessionId)
                        && tenantId.equals(requestedTenantId)
                        ? Optional.of(AuthorizedRequestContext.support(
                        requestedTenantId, requestedSessionId, correlationId))
                        : Optional.empty();
            }
        };
        RequestContextAuthenticationFilter filter = filter(null, tenantId, supportSessions);

        MockHttpServletRequest read = new MockHttpServletRequest(
                "GET", "/api/v1/tenants/" + tenantId + "/merchants");
        read.addHeader(HttpHeaders.AUTHORIZATION, "Bearer valid");
        read.addHeader(RequestContextAuthenticationFilter.SUPPORT_SESSION_HEADER,
                supportSessionId.toString());
        read.setAttribute(RequestCorrelationFilter.CORRELATION_ID, "support-correlation");
        MockHttpServletResponse readResponse = new MockHttpServletResponse();
        AtomicBoolean readContinued = new AtomicBoolean();

        filter.doFilter(read, readResponse,
                (ignoredRequest, ignoredResponse) -> readContinued.set(true));

        assertThat(readResponse.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(readContinued).isTrue();
        assertThat(read.getAttribute(RequestContextAuthenticationFilter.CONTEXT_ATTRIBUTE))
                .isEqualTo(AuthorizedRequestContext.support(
                        tenantId, supportSessionId, "support-correlation"));

        MockHttpServletRequest write = new MockHttpServletRequest(
                "POST", "/api/v1/tenants/" + tenantId + "/memberships/"
                        + UUID.randomUUID() + "/invitation/revoke");
        write.addHeader(HttpHeaders.AUTHORIZATION, "Bearer valid");
        write.addHeader(RequestContextAuthenticationFilter.SUPPORT_SESSION_HEADER,
                supportSessionId.toString());
        MockHttpServletResponse writeResponse = new MockHttpServletResponse();
        AtomicBoolean writeContinued = new AtomicBoolean();

        filter.doFilter(write, writeResponse,
                (ignoredRequest, ignoredResponse) -> writeContinued.set(true));

        assertThat(writeResponse.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(writeContinued).isFalse();
    }

    @Test
    void servicePaymentDoesNotRequireOrTrustTheTenantSelectionHeader() throws Exception {
        UUID credentialTenantId = UUID.randomUUID();
        UUID credentialMerchantId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        JwtPrincipalParser parser = new JwtPrincipalParser(token -> new Jwt(
                "valid",
                NOW.minusSeconds(60),
                NOW.plusSeconds(300),
                Map.of("alg", "none"),
                Map.of(
                        "iss", ISSUER,
                        "sub", "service-subject",
                        "aud", Set.of(AUDIENCE),
                        "preferred_username", "service-account-ledgerops",
                        "azp", "ledgerops-sandbox-client"
                )
        ), ISSUER, AUDIENCE, Clock.fixed(NOW, ZoneOffset.UTC));
        ApplicationUserRepository users = emptyUsers();
        RequestContextService service = new RequestContextService(
                users,
                (applicationUserId, principalType, serviceClientId, selectedTenantId) ->
                        applicationUserId == null
                                && principalType == PrincipalType.SERVICE
                                && "ledgerops-sandbox-client".equals(serviceClientId)
                                && selectedTenantId == null
                                ? Optional.of(new AuthorizedTenantContext(
                                credentialTenantId,
                                ScopeMode.MERCHANT_SET,
                                Set.of(credentialMerchantId),
                                Set.of(Permission.PAYMENT_CREATE),
                                credentialId
                        ))
                                : Optional.empty()
        );
        RequestContextAuthenticationFilter filter = new RequestContextAuthenticationFilter(
                parser,
                service,
                new ObjectMapper()
        );
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/payments");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer valid");
        request.addHeader(RequestContextAuthenticationFilter.TENANT_SELECTION_HEADER,
                UUID.randomUUID().toString());
        request.setAttribute(RequestCorrelationFilter.CORRELATION_ID, "service-correlation");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> continued.set(true));

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(continued).isTrue();
        var context = (com.ledgerops.identity.api.AuthorizedRequestContext)
                request.getAttribute(RequestContextAuthenticationFilter.CONTEXT_ATTRIBUTE);
        assertThat(context.tenantId()).isEqualTo(credentialTenantId);
        assertThat(context.merchantIds()).containsExactly(credentialMerchantId);
        assertThat(context.serviceCredentialId()).isEqualTo(credentialId);
    }

    private void assertUnauthorized(String authorization, String code) throws Exception {
        UUID tenantId = UUID.randomUUID();
        RequestContextAuthenticationFilter filter = filter(null, tenantId);
        MockHttpServletRequest request = tenantRequest(tenantId);
        request.setAttribute(RequestContextAuthenticationFilter.AUTHENTICATION_REQUIRED_ATTRIBUTE, true);
        if (authorization != null) {
            request.addHeader(HttpHeaders.AUTHORIZATION, authorization);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> { });

        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString()).contains(code);
    }

    private RequestContextAuthenticationFilter filter(ApplicationUser user, UUID tenantId) {
        return filter(user, tenantId, null);
    }

    private RequestContextAuthenticationFilter filter(
            ApplicationUser user,
            UUID tenantId,
            SupportSessionPort supportSessions
    ) {
        JwtPrincipalParser parser = new JwtPrincipalParser(token -> {
            if ("invalid".equals(token)) {
                throw new JwtException("invalid");
            }
            return new Jwt("valid", NOW.minusSeconds(60), NOW.plusSeconds(300),
                    Map.of("alg", "none"), Map.of(
                    "iss", ISSUER, "sub", "subject-1", "aud", Set.of(AUDIENCE),
                    "preferred_username", "alice"));
        }, ISSUER, AUDIENCE, Clock.fixed(NOW, ZoneOffset.UTC));
        ApplicationUserRepository users = new ApplicationUserRepository() {
            @Override public ApplicationUser save(ApplicationUser applicationUser) { return applicationUser; }
            @Override public Optional<ApplicationUser> findById(ApplicationUserId id) { return Optional.empty(); }
            @Override public Optional<ApplicationUser> findByKeycloakIdentity(KeycloakIdentity identity) {
                return user != null && user.keycloakIdentity().equals(identity) ? Optional.of(user) : Optional.empty();
            }
        };
        RequestContextService service = new RequestContextService(users, (id, type, client, selectedTenant) ->
                user != null && selectedTenant.equals(tenantId)
                        ? Optional.of(new AuthorizedTenantContext(tenantId, ScopeMode.TENANT_WIDE,
                        Set.of(), Set.of(Permission.PAYMENT_READ), null))
                        : Optional.empty());
        return new RequestContextAuthenticationFilter(
                parser, service, new ObjectMapper(), supportSessions);
    }

    private ApplicationUserRepository emptyUsers() {
        return new ApplicationUserRepository() {
            @Override public ApplicationUser save(ApplicationUser applicationUser) { return applicationUser; }
            @Override public Optional<ApplicationUser> findById(ApplicationUserId id) { return Optional.empty(); }
            @Override public Optional<ApplicationUser> findByKeycloakIdentity(KeycloakIdentity identity) {
                return Optional.empty();
            }
        };
    }

    private MockHttpServletRequest tenantRequest(UUID tenantId) {
        return new MockHttpServletRequest("GET", "/api/v1/tenants/" + tenantId);
    }
}
