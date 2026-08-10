package com.ledgerops.administration.support.api;

import com.ledgerops.ApiProblemFactory;
import com.ledgerops.identity.api.PlatformAuthorizationException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice(assignableTypes = SupportSessionController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class SupportSessionProblemHandler {

    @ExceptionHandler(PlatformAuthorizationException.class)
    ProblemDetail handlePlatformAuthorization(PlatformAuthorizationException exception) {
        return problem(
                HttpStatus.FORBIDDEN,
                "Support access denied",
                "Only an authenticated Platform Admin may start support mode",
                "support-authorization-denied",
                "No support session was created.",
                false,
                "Use an authenticated Platform Admin identity and retry."
        );
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class
    })
    ProblemDetail handleMalformedRequest(Exception exception) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Invalid support session request",
                exception.getMessage(),
                "invalid-support-session-request",
                "No support session was created.",
                false,
                "Provide a Tenant, confirmation, and a bounded reason."
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException exception) {
        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST,
                "Support session validation failed",
                "One or more request fields are invalid",
                "support-session-validation",
                "No support session was created.",
                false,
                "Correct the listed fields and submit the request again."
        );
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(
                error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage())
        );
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail handleEligibility(IllegalStateException exception) {
        return problem(
                HttpStatus.FORBIDDEN,
                "Support session cannot be started",
                exception.getMessage(),
                "support-session-not-eligible",
                "No support session was created.",
                false,
                "Reauthenticate recently, then submit a new support request."
        );
    }

    private ProblemDetail problem(
            HttpStatus status,
            String title,
            String detail,
            String type,
            String effect,
            boolean retryable,
            String nextAction
    ) {
        return ApiProblemFactory.create(status, title, detail, type, effect, retryable, nextAction);
    }
}
