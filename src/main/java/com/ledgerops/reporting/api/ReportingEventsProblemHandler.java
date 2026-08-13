package com.ledgerops.reporting.api;

import com.ledgerops.ApiProblemFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ReportingEventsController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class ReportingEventsProblemHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleInvalidRequest(IllegalArgumentException exception) {
        return ApiProblemFactory.create(
                HttpStatus.BAD_REQUEST,
                "Invalid Reporting events request",
                exception.getMessage(),
                "invalid-reporting-events-request",
                "No source-module state was changed.",
                false,
                "Correct the request and retry.");
    }
}
