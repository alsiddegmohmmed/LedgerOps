package com.ledgerops.administration.merchant.api;

import com.ledgerops.ApiProblemFactory;
import com.ledgerops.merchant.api.MerchantNotFoundException;
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

@RestControllerAdvice(assignableTypes = MerchantLifecycleController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class MerchantLifecycleProblemHandler {

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class
    })
    ProblemDetail handleMalformedRequest(Exception exception) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Invalid Merchant lifecycle request",
                "The request contains malformed JSON or an invalid value",
                "invalid-merchant-lifecycle-request",
                "The Merchant was not changed.",
                false,
                "Correct the request and submit it again."
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException exception) {
        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST,
                "Merchant lifecycle validation failed",
                "One or more request fields are invalid",
                "merchant-lifecycle-validation",
                "The Merchant was not changed.",
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

    @ExceptionHandler(MerchantNotFoundException.class)
    ProblemDetail handleNotFound(MerchantNotFoundException exception) {
        return problem(
                HttpStatus.NOT_FOUND,
                "Merchant not found",
                "The requested Merchant is unavailable",
                "merchant-not-found",
                "The Merchant was not changed.",
                false,
                "Refresh the Merchant list and retry only if it is still available."
        );
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail handleConflict(IllegalStateException exception) {
        return problem(
                HttpStatus.CONFLICT,
                "Merchant lifecycle transition is not allowed",
                exception.getMessage(),
                "merchant-lifecycle-conflict",
                "The Merchant was not changed.",
                false,
                "Refresh the Merchant state before retrying the action."
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
