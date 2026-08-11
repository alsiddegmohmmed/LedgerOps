package com.ledgerops.reconciliation.api;

import com.ledgerops.ApiProblemFactory;
import com.ledgerops.identity.api.AuthorizationPermissionDeniedException;
import com.ledgerops.identity.api.AuthorizationResourceNotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = ReconciliationRunController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class ReconciliationRunProblemHandler {

    @ExceptionHandler(AuthorizationResourceNotFoundException.class)
    ProblemDetail notFound(AuthorizationResourceNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "Reconciliation resource not found",
                "The requested reconciliation resource is unavailable", "reconciliation-not-found",
                "No reconciliation state was disclosed or changed.", false,
                "Use an authorized Tenant and reconciliation identifier.");
    }

    @ExceptionHandler(AuthorizationPermissionDeniedException.class)
    ProblemDetail forbidden(AuthorizationPermissionDeniedException exception) {
        return problem(HttpStatus.FORBIDDEN, "Reconciliation action is not authorized",
                exception.getMessage(), "reconciliation-forbidden",
                "No reconciliation state was changed.", false,
                "Use the required Tenant-wide reconciliation permission.");
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail conflict(IllegalStateException exception) {
        return problem(HttpStatus.CONFLICT, "Reconciliation state conflict",
                exception.getMessage(), "reconciliation-state-conflict",
                "No incompatible reconciliation state was committed.", true,
                "Refresh the run and retry only the documented next action.");
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentNotValidException.class,
            MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class
    })
    ProblemDetail invalid(Exception exception) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid reconciliation request",
                "The request contains malformed data or invalid values", "invalid-reconciliation-request",
                "No reconciliation state was changed.", false,
                "Correct the request and submit it again.");
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail, String type,
                                  String effect, boolean retryable, String nextAction) {
        return ApiProblemFactory.create(status, title, detail, type, effect, retryable, nextAction);
    }
}
