package com.ledgerops.tenancy.api;

import com.ledgerops.ApiProblemFactory;
import com.ledgerops.tenancy.application.OperationalContactNotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice(assignableTypes = OperationalContactController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class OperationalContactProblemHandler {

    @ExceptionHandler(OperationalContactNotFoundException.class)
    ProblemDetail handleNotFound(OperationalContactNotFoundException exception) {
        ProblemDetail problem = problem(
                HttpStatus.NOT_FOUND,
                "Operational contact not found",
                exception.getMessage(),
                "operational-contact-not-found",
                "No operational contact was read or changed.",
                false,
                "Verify the contactId and Tenant context before retrying."
        );
        problem.setProperty("tenantId", exception.tenantId().value());
        problem.setProperty("contactId", exception.contactId());
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleInvalidValue(IllegalArgumentException exception) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Invalid operational contact request",
                exception.getMessage(),
                "invalid-operational-contact-request",
                "No operational contact was changed.",
                false,
                "Correct the contact values and submit the request again."
        );
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            HttpMessageConversionException.class,
            MethodArgumentTypeMismatchException.class
    })
    ProblemDetail handleMalformedRequest(Exception exception) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Invalid operational contact request",
                "The request contains malformed JSON or an invalid path value",
                "invalid-operational-contact-request",
                "No operational contact was changed.",
                false,
                "Correct the request and submit it again."
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException exception) {
        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST,
                "Operational contact validation failed",
                "One or more contact fields are invalid",
                "operational-contact-validation",
                "No operational contact was changed.",
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

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail handleStateConflict(IllegalStateException exception) {
        return problem(
                HttpStatus.CONFLICT,
                "Operational contact cannot change",
                exception.getMessage(),
                "operational-contact-state-conflict",
                "No operational contact was changed.",
                false,
                "Refresh the Tenant status and retry only when contact changes are allowed."
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
