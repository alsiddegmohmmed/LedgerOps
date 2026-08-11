package com.ledgerops.reconciliation.api;

import com.ledgerops.ApiProblemFactory;
import com.ledgerops.identity.api.AuthorizationPermissionDeniedException;
import com.ledgerops.identity.api.AuthorizationResourceNotFoundException;
import com.ledgerops.reconciliation.application.SettlementStructuralException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = SettlementBatchController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class SettlementBatchProblemHandler {

    @ExceptionHandler(AuthorizationResourceNotFoundException.class)
    ProblemDetail notFound(AuthorizationResourceNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "Settlement batch not found",
                "The requested settlement batch is unavailable", "settlement-batch-not-found",
                "No settlement state was disclosed or changed.", false,
                "Use an authorized Tenant and settlement batch identifier.");
    }

    @ExceptionHandler(AuthorizationPermissionDeniedException.class)
    ProblemDetail forbidden(AuthorizationPermissionDeniedException exception) {
        return problem(HttpStatus.FORBIDDEN, "Settlement action is not authorized",
                exception.getMessage(), "settlement-forbidden",
                "No settlement state was changed.", false,
                "Use the Reconciliation Analyst permission required by this action.");
    }

    @ExceptionHandler(SettlementStructuralException.class)
    ProblemDetail structural(SettlementStructuralException exception) {
        ProblemDetail value = problem(HttpStatus.BAD_REQUEST, "Settlement file rejected",
                exception.getMessage(), "settlement-file-rejected",
                "No normalized settlement records were imported.", false,
                "Correct the file according to the approved settlement CSV contract.");
        value.setProperty("reasonCode", exception.reasonCode().name());
        return value;
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail conflict(IllegalStateException exception) {
        return problem(HttpStatus.CONFLICT, "Settlement batch state conflict",
                exception.getMessage(), "settlement-batch-state-conflict",
                "No additional settlement state was changed.", true,
                "Refresh the batch and retry only the documented next action.");
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentNotValidException.class,
            MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class
    })
    ProblemDetail invalid(Exception exception) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid settlement request",
                "The request contains malformed data or invalid values", "invalid-settlement-request",
                "No settlement state was changed.", false,
                "Correct the request and submit it again.");
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail, String type,
                                  String effect, boolean retryable, String nextAction) {
        return ApiProblemFactory.create(status, title, detail, type, effect, retryable, nextAction);
    }
}
