package com.ledgerops.payment.api;

import com.ledgerops.ApiProblemFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = PaymentRetryNowController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class PaymentRetryNowProblemHandler {

    @ExceptionHandler(PaymentOperationConflictException.class)
    ProblemDetail conflict(PaymentOperationConflictException exception) {
        return ApiProblemFactory.create(
                HttpStatus.CONFLICT,
                "Payment retry is not available",
                exception.getMessage(),
                "payment-retry-not-available",
                "No Payment attempt, retry request, or outbox message was created.",
                false,
                "Inspect the current Payment and Provider recovery state before retrying.");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalid(MethodArgumentNotValidException exception) {
        return ApiProblemFactory.create(
                HttpStatus.BAD_REQUEST,
                "Invalid Payment retry request",
                "Confirmation and a reason are required.",
                "payment-retry-request-invalid",
                "No retry work was changed.",
                false,
                "Confirm the action and provide an operator reason.");
    }
}
