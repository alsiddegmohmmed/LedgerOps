package com.ledgerops.payment.api;

import com.ledgerops.ApiProblemFactory;
import com.ledgerops.identity.api.AuthorizationPermissionDeniedException;
import com.ledgerops.identity.api.AuthorizationResourceNotFoundException;
import com.ledgerops.payment.application.PaymentCaseResolutionConsistencyException;
import com.ledgerops.payment.application.PaymentLifecycleNotFoundException;
import com.ledgerops.payment.application.PaymentLifecycleStateException;
import com.ledgerops.payment.application.PaymentOptimisticConcurrencyException;
import com.ledgerops.payment.application.PaymentRiskDecisionConsistencyException;
import com.ledgerops.payment.application.PaymentRiskReviewNotFoundException;
import com.ledgerops.risk.api.RiskReviewAuthorizationFailure;
import com.ledgerops.risk.api.RiskReviewConflictException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ManualRiskReviewController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class ManualRiskReviewProblemHandler {

    @ExceptionHandler({
            AuthorizationResourceNotFoundException.class,
            PaymentRiskReviewNotFoundException.class,
            PaymentLifecycleNotFoundException.class
    })
    ProblemDetail handleNotFound(RuntimeException exception) {
        return problem(HttpStatus.NOT_FOUND, "Risk review not found", exception.getMessage(),
                "risk-review-not-found", "No RiskReview or Payment state was changed.",
                false, "Refresh the Risk queue and choose an authorized review.");
    }

    @ExceptionHandler({
            AuthorizationPermissionDeniedException.class,
            SecurityException.class,
            RiskReviewAuthorizationFailure.class
    })
    ProblemDetail handleForbidden(RuntimeException exception) {
        return problem(HttpStatus.FORBIDDEN, "Risk review action is not authorized", exception.getMessage(),
                "risk-review-forbidden", "No RiskReview or Payment state was changed.",
                false, "Use an assigned permission and an authorized Merchant scope.");
    }

    @ExceptionHandler({
            PaymentCaseResolutionConsistencyException.class,
            PaymentRiskDecisionConsistencyException.class,
            PaymentLifecycleStateException.class,
            PaymentOptimisticConcurrencyException.class,
            RiskReviewConflictException.class
    })
    ProblemDetail handleConflict(RuntimeException exception) {
        return problem(HttpStatus.CONFLICT, "Risk review action conflicts with current state",
                exception.getMessage(), "risk-review-state-conflict",
                "The RiskReview and Payment transaction was rolled back.", true,
                "Refresh the review and retry only if the documented state transition is still valid.");
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentNotValidException.class,
            IllegalArgumentException.class
    })
    ProblemDetail handleInvalidRequest(Exception exception) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid RiskReview request",
                "The request contains malformed JSON or invalid values", "invalid-risk-review-request",
                "No RiskReview or Payment state was changed.", false,
                "Correct the request and submit it again.");
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail, String type,
                                  String effect, boolean retryable, String nextAction) {
        return ApiProblemFactory.create(status, title, detail, type, effect, retryable, nextAction);
    }
}
