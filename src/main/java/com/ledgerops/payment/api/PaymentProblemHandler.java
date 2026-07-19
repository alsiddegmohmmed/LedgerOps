package com.ledgerops.payment.api;

import com.ledgerops.RequestCorrelationFilter;
import com.ledgerops.payment.application.PaymentIdempotencyConflictException;
import com.ledgerops.payment.application.PaymentReferenceUnavailableException;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@RestControllerAdvice(assignableTypes = PaymentController.class)
class PaymentProblemHandler {

    @ExceptionHandler(PaymentIdempotencyConflictException.class)
    ProblemDetail handleIdempotencyConflict(
            PaymentIdempotencyConflictException exception
    ) {
        ProblemDetail problem = problem(
                HttpStatus.CONFLICT,
                "Payment idempotency conflict",
                exception.getMessage(),
                "payment-idempotency-conflict"
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
                "payment-reference-unavailable"
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
                "invalid-payment-request"
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handleMalformedRequest(HttpMessageNotReadableException exception) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Invalid payment request",
                "The request contains malformed JSON or an invalid value",
                "invalid-payment-request"
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException exception) {
        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST,
                "Payment request validation failed",
                "One or more request fields are invalid",
                "payment-request-validation"
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
            String type
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create("urn:ledgerops:problem:" + type));
        problem.setProperty("code", type.replace('-', '_').toUpperCase(Locale.ROOT));
        problem.setProperty("traceId", MDC.get(RequestCorrelationFilter.TRACE_ID));
        return problem;
    }
}
