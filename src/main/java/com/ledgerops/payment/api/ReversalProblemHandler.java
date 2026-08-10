package com.ledgerops.payment.api;

import com.ledgerops.ApiProblemFactory;
import com.ledgerops.identity.api.AuthorizationPermissionDeniedException;
import com.ledgerops.identity.api.AuthorizationResourceNotFoundException;
import com.ledgerops.payment.application.ReversalAlreadyExistsException;
import com.ledgerops.payment.application.ReversalRequestConsistencyException;
import com.ledgerops.payment.application.ReversalRetryConsistencyException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ReversalController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class ReversalProblemHandler {

    @ExceptionHandler(AuthorizationResourceNotFoundException.class)
    ProblemDetail notFound(AuthorizationResourceNotFoundException exception) {
        return problem(
                HttpStatus.NOT_FOUND,
                "Reversal not found",
                exception.getMessage(),
                "reversal-not-found",
                "No Reversal or Payment state was changed.",
                false,
                "Use an authorized Tenant and Merchant scope."
        );
    }

    @ExceptionHandler(AuthorizationPermissionDeniedException.class)
    ProblemDetail forbidden(AuthorizationPermissionDeniedException exception) {
        return problem(
                HttpStatus.FORBIDDEN,
                "Reversal action is not authorized",
                exception.getMessage(),
                "reversal-forbidden",
                "No Reversal, Payment, attempt, or Provider work was changed.",
                false,
                "Use the required Reversal permission and current Merchant scope."
        );
    }

    @ExceptionHandler({
            ReversalAlreadyExistsException.class,
            ReversalRequestConsistencyException.class,
            ReversalRetryConsistencyException.class
    })
    ProblemDetail conflict(RuntimeException exception) {
        return problem(
                HttpStatus.CONFLICT,
                "Reversal action conflicts with current state",
                exception.getMessage(),
                "reversal-state-conflict",
                "The transaction was rolled back and no partial Reversal action was committed.",
                true,
                "Refresh the Reversal and retry only if the documented state transition remains valid."
        );
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentNotValidException.class,
            IllegalArgumentException.class
    })
    ProblemDetail invalid(Exception exception) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Invalid Reversal request",
                "Confirmation, identifiers, and a reason are required.",
                "invalid-reversal-request",
                "No Reversal or retry work was changed.",
                false,
                "Correct the request and submit it again."
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
