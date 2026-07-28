package com.ledgerops.identity.api;

import com.ledgerops.identity.application.UnknownApplicationIdentityException;
import jakarta.servlet.http.HttpServletRequest;

public final class AuthorizedRequestContextRequest {

    private static final String PRINCIPAL_ATTRIBUTE =
            AuthorizedRequestContextRequest.class.getName() + ".principal";

    private AuthorizedRequestContextRequest() {
    }

    public static AuthorizedRequestContext required(HttpServletRequest request) {
        Object value = request.getAttribute(AuthorizedRequestContext.class.getName());
        if (value instanceof AuthorizedRequestContext context) {
            return context;
        }
        throw new UnknownApplicationIdentityException();
    }

    public static AuthenticatedPrincipal principal(HttpServletRequest request) {
        Object value = request.getAttribute(PRINCIPAL_ATTRIBUTE);
        if (value instanceof AuthenticatedPrincipal principal) {
            return principal;
        }
        throw new UnknownApplicationIdentityException();
    }

    public static String principalAttribute() {
        return PRINCIPAL_ATTRIBUTE;
    }
}
