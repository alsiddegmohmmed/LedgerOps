package com.ledgerops.payment.api;

import com.ledgerops.ApiProblemFactory;
import com.ledgerops.payment.application.InvalidPaymentCursorException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = PaymentQueryController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class PaymentQueryProblemHandler {

    @ExceptionHandler(InvalidPaymentCursorException.class)
    ProblemDetail handleInvalidCursor(InvalidPaymentCursorException exception) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Invalid Payment page cursor",
                exception.getMessage(),
                "invalid-payment-cursor",
                "No Payment state was changed.",
                "Restart the Payment search without the cursor.");
    }

    @ExceptionHandler({
            IllegalArgumentException.class,
            MethodArgumentTypeMismatchException.class
    })
    ProblemDetail handleInvalidQuery(Exception exception) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Invalid Payment search",
                "The Payment search contains an invalid filter or pagination value",
                "invalid-payment-search",
                "No Payment state was changed.",
                "Correct the filters and retry the search.");
    }

    private ProblemDetail problem(
            HttpStatus status,
            String title,
            String detail,
            String type,
            String effect,
            String nextAction
    ) {
        return ApiProblemFactory.create(
                status, title, detail, type, effect, false, nextAction);
    }
}
