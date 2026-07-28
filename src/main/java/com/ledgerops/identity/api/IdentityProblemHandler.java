package com.ledgerops.identity.api;

import com.ledgerops.ApiProblemFactory;
import com.ledgerops.identity.application.UnknownApplicationIdentityException;
import com.ledgerops.identity.application.InactiveApplicationUserException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class IdentityProblemHandler {

    @ExceptionHandler(UnknownApplicationIdentityException.class)
    ProblemDetail handleUnknownIdentity() {
        return problem(HttpStatus.UNAUTHORIZED, "Authentication required",
                "A valid authenticated identity is required", "authentication-required",
                "No protected operation was performed.");
    }

    @ExceptionHandler(InactiveApplicationUserException.class)
    ProblemDetail handleInactiveIdentity() {
        return problem(HttpStatus.FORBIDDEN, "Authorization denied",
                "The authenticated identity is inactive", "authorization-denied",
                "No protected operation was performed.");
    }

    @ExceptionHandler(AuthorizationPermissionDeniedException.class)
    ProblemDetail handleInsufficientPermission(AuthorizationPermissionDeniedException exception) {
        return problem(HttpStatus.FORBIDDEN, "Authorization denied", exception.getMessage(),
                "authorization-denied", "No protected operation was performed.");
    }

    @ExceptionHandler(AuthorizationResourceNotFoundException.class)
    ProblemDetail handleOutOfScope(Exception exception) {
        return problem(HttpStatus.NOT_FOUND, "Resource not found",
                "The requested resource is unavailable", "resource-not-found",
                "No protected resource existence was disclosed.");
    }

    private ProblemDetail problem(
            HttpStatus status,
            String title,
            String detail,
            String type,
            String effect
    ) {
        return ApiProblemFactory.create(status, title, detail, type, effect, false,
                "Correct the request and retry.");
    }
}
