package com.ledgerops.casework.api;

import com.ledgerops.ApiProblemFactory;
import com.ledgerops.casework.application.CaseNotFoundException;
import com.ledgerops.casework.application.CaseResolutionConsistencyException;
import com.ledgerops.casework.domain.CaseResolutionException;
import com.ledgerops.casework.domain.CaseStateException;
import com.ledgerops.identity.api.AuthorizationPermissionDeniedException;
import com.ledgerops.identity.api.AuthorizationResourceNotFoundException;
import com.ledgerops.payment.api.PaymentOperationConflictException;
import com.ledgerops.payment.api.PaymentOperationNotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = CaseworkController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class CaseworkProblemHandler {

    @ExceptionHandler({
            AuthorizationResourceNotFoundException.class,
            CaseNotFoundException.class,
            PaymentOperationNotFoundException.class
    })
    ProblemDetail handleNotFound(RuntimeException exception) {
        return problem(HttpStatus.NOT_FOUND, "Case not found", exception.getMessage(),
                "case-not-found", "No Case, Payment, or RiskReview state was changed.",
                false, "Refresh the Case queue and choose an authorized Case.");
    }

    @ExceptionHandler({AuthorizationPermissionDeniedException.class, SecurityException.class})
    ProblemDetail handleForbidden(RuntimeException exception) {
        return problem(HttpStatus.FORBIDDEN, "Case action is not authorized", exception.getMessage(),
                "case-forbidden", "No Case or Payment state was changed.", false,
                "Use an authorized Case permission and Merchant scope.");
    }

    @ExceptionHandler({
            CaseResolutionConsistencyException.class,
            CaseResolutionException.class,
            CaseStateException.class,
            PaymentOperationConflictException.class
    })
    ProblemDetail handleConflict(RuntimeException exception) {
        return problem(HttpStatus.CONFLICT, "Case action conflicts with current state",
                exception.getMessage(), "case-state-conflict",
                "The Case and any related Payment transaction was rolled back.", true,
                "Refresh the Case and retry only if the documented transition is still valid.");
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentNotValidException.class,
            IllegalArgumentException.class
    })
    ProblemDetail handleInvalidRequest(Exception exception) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid Case request",
                "The request contains malformed JSON or invalid values", "invalid-case-request",
                "No Case or Payment state was changed.", false,
                "Correct the request and submit it again.");
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail, String type,
                                  String effect, boolean retryable, String nextAction) {
        return ApiProblemFactory.create(status, title, detail, type, effect, retryable, nextAction);
    }
}
