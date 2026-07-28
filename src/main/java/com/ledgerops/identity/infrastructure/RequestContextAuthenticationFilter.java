package com.ledgerops.identity.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerops.ApiProblemFactory;
import com.ledgerops.RequestCorrelationFilter;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.identity.api.AuthorizedRequestContextRequest;
import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.application.InactiveApplicationUserException;
import com.ledgerops.identity.application.InvalidTenantSelectionException;
import com.ledgerops.identity.application.RequestContextService;
import com.ledgerops.identity.application.UnknownApplicationIdentityException;
import com.ledgerops.identity.application.ValidatedPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;
import org.slf4j.MDC;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RequestContextAuthenticationFilter extends OncePerRequestFilter {

    static final String CONTEXT_ATTRIBUTE = AuthorizedRequestContext.class.getName();
    static final String AUTHENTICATION_REQUIRED_ATTRIBUTE =
            RequestContextAuthenticationFilter.class.getName() + ".required";
    private static final Pattern TENANT_PATH = Pattern.compile("^/api/v1/tenants/([0-9a-fA-F-]{36})(?:/.*)?$");
    private static final Pattern TENANT_READ_PATH = Pattern.compile("^/api/v1/tenants/[0-9a-fA-F-]{36}$");
    private static final String PAYMENT_PATH = "/api/v1/payments";
    static final String TENANT_SELECTION_HEADER = "X-Tenant-Id";

    private final JwtPrincipalParser jwtPrincipalParser;
    private final RequestContextService requestContextService;
    private final ObjectMapper objectMapper;

    RequestContextAuthenticationFilter(
            JwtPrincipalParser jwtPrincipalParser,
            RequestContextService requestContextService,
            ObjectMapper objectMapper
    ) {
        this.jwtPrincipalParser = jwtPrincipalParser;
        this.requestContextService = requestContextService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isProtectedPath(request);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")
                || authorization.substring(7).isBlank()) {
            writeProblem(response, HttpStatus.UNAUTHORIZED, "Authentication required",
                    "A valid bearer token is required", "authentication_required");
            return;
        }

        try {
            ValidatedPrincipal principal = jwtPrincipalParser.parse(authorization.substring(7));
            request.setAttribute(AuthorizedRequestContextRequest.principalAttribute(),
                    new AuthenticatedPrincipal(
                            principal.principalType().name(),
                            principal.keycloakIdentity().issuer(),
                            principal.keycloakIdentity().subject()
                    ));
            AuthorizedRequestContext context = requestContextService.create(
                    principal,
                    tenantId(request),
                    correlationId(request)
            );
            request.setAttribute(CONTEXT_ATTRIBUTE, context);
            filterChain.doFilter(request, response);
        } catch (InvalidJwtPrincipalException exception) {
            writeProblem(response, HttpStatus.UNAUTHORIZED, "Invalid authentication",
                    "The bearer token is invalid", "invalid_authentication");
        } catch (UnknownApplicationIdentityException | InactiveApplicationUserException exception) {
            writeProblem(response, HttpStatus.FORBIDDEN, "Authorization denied",
                    "The authenticated identity is not authorized", "authorization_denied");
        } catch (InvalidTenantSelectionException exception) {
            writeProblem(response, HttpStatus.NOT_FOUND, "Resource not found",
                    "The requested Tenant context is unavailable", "tenant_context_not_found");
        }
    }

    private UUID tenantId(HttpServletRequest request) {
        Matcher matcher = TENANT_PATH.matcher(request.getRequestURI());
        if (matcher.matches()) {
            return parseUuid(matcher.group(1));
        }
        if (PAYMENT_PATH.equals(request.getRequestURI())) {
            return parseUuid(request.getHeader(TENANT_SELECTION_HEADER));
        }
        return null;
    }

    private boolean isProtectedPath(HttpServletRequest request) {
        return ("GET".equals(request.getMethod())
                && TENANT_READ_PATH.matcher(request.getRequestURI()).matches())
                || ("POST".equals(request.getMethod()) && PAYMENT_PATH.equals(request.getRequestURI()));
    }

    private UUID parseUuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String correlationId(HttpServletRequest request) {
        Object correlationId = request.getAttribute(RequestCorrelationFilter.CORRELATION_ID);
        return correlationId instanceof String value ? value : MDC.get(RequestCorrelationFilter.CORRELATION_ID);
    }

    private void writeProblem(
            HttpServletResponse response,
            HttpStatus status,
            String title,
            String detail,
            String type
    ) throws IOException {
        ProblemDetail problem = ApiProblemFactory.create(status, title, detail, type,
                "No protected operation was performed", false, "Correct the request and retry");
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
