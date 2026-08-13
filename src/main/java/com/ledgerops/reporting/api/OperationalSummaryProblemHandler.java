package com.ledgerops.reporting.api;

import com.ledgerops.ApiProblemFactory;
import com.ledgerops.reporting.application.InvalidOperationalSummaryCursorException;
import com.ledgerops.reporting.application.ReportingNotReadyException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = OperationalSummaryController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class OperationalSummaryProblemHandler {

    @ExceptionHandler(ReportingNotReadyException.class)
    ProblemDetail handleNotReady(ReportingNotReadyException exception) {
        return ApiProblemFactory.create(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Reporting projection is not ready",
                exception.getMessage(),
                "reporting-not-ready",
                "No source-module state was changed.",
                true,
                "Retry after a complete Reporting projection generation is available.");
    }

    @ExceptionHandler(InvalidOperationalSummaryCursorException.class)
    ProblemDetail handleInvalidSummaryCursor(InvalidOperationalSummaryCursorException exception) {
        return ApiProblemFactory.create(
                HttpStatus.BAD_REQUEST,
                "Invalid operational-summary cursor",
                exception.getMessage(),
                "invalid-operational-summary-cursor",
                "No source-module state was changed.",
                false,
                "Restart the drill-down without the cursor.");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleInvalidRequest(IllegalArgumentException exception) {
        return ApiProblemFactory.create(
                HttpStatus.BAD_REQUEST,
                "Invalid operational-summary request",
                exception.getMessage(),
                "invalid-operational-summary-request",
                "No source-module state was changed.",
                false,
                "Correct the request and retry.");
    }
}
