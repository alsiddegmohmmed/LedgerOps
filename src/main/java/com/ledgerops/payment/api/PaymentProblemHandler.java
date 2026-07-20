package com.ledgerops.payment.api;

import com.ledgerops.ApiProblemFactory;
import com.ledgerops.payment.application.PaymentIdempotencyConflictException;
import com.ledgerops.payment.application.PaymentReferenceUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice(assignableTypes = PaymentController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class PaymentProblemHandler {

    @ExceptionHandler(PaymentIdempotencyConflictException.class)
    ProblemDetail handleIdempotencyConflict(
            PaymentIdempotencyConflictException exception
    ) {
        ProblemDetail problem = problem(
                HttpStatus.CONFLICT,
                "Payment idempotency conflict",
                exception.getMessage(),
                "payment-idempotency-conflict",
                "The existing Payment was unchanged and no new Payment was created.",
                false,
                "Reuse the original request content or choose a new idempotency key."
        );
        problem.setProperty("tenantId", exception.tenantId());
        return problem;
    }

    @ExceptionHandler(PaymentReferenceUnavailableException.class)
    ProblemDetail handleReferenceUnavailable(
            PaymentReferenceUnavailableException exception
    ) {
        ProblemDetail problem = problem(
                HttpStatus.CONFLICT,
                "Payment reference unavailable",
                exception.getMessage(),
                "payment-reference-unavailable",
                "No Payment was created.",
                false,
                "Use active tenant, merchant, and customer references before retrying."
        );
        problem.setProperty("referenceType", exception.referenceType());
        problem.setProperty("reason", exception.reason());
        return problem;
    }

    @ExceptionHandler(InvalidPaymentRequestException.class)
    ProblemDetail handleInvalidRequest(InvalidPaymentRequestException exception) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Invalid payment request",
                exception.getMessage(),
                "invalid-payment-request",
                "No Payment was created.",
                false,
                "Correct the request values and submit it again."
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handleMalformedRequest(HttpMessageNotReadableException exception) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Invalid payment request",
                "The request contains malformed JSON or an invalid value",
                "invalid-payment-request",
                "No Payment was created.",
                false,
                "Correct the JSON and submit the request again."
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException exception) {
        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST,
                "Payment request validation failed",
                "One or more request fields are invalid",
                "payment-request-validation",
                "No Payment was created.",
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

    private ProblemDetail problem(
            HttpStatus status,
            String title,
            String detail,
            String type,
            String effect,
            boolean retryable,
            String nextAction
    ) {
        return ApiProblemFactory.create(
                status,
                title,
                detail,
                type,
                effect,
                retryable,
                nextAction
        );
    }
}
